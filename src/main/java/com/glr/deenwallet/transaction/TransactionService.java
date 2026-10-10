package com.glr.deenwallet.transaction;

import com.glr.deenwallet.email.EmailService;
import com.glr.deenwallet.monime.CreatePaymentCodeRequest;
import com.glr.deenwallet.monime.CreatePayoutRequest;
import com.glr.deenwallet.monime.Money;
import com.glr.deenwallet.monime.MonimeClient;
import com.glr.deenwallet.monime.MonimeProperties;
import com.glr.deenwallet.monime.PaymentCodeResult;
import com.glr.deenwallet.monime.PayoutResult;
import com.glr.deenwallet.monime.ProviderKycResult;
import com.glr.deenwallet.notification.NotificationService;
import com.glr.deenwallet.recipient.SavedRecipient;
import com.glr.deenwallet.recipient.SavedRecipientRepository;
import com.glr.deenwallet.recipient.SavedRecipientType;
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.config.ProviderConfig;
import com.glr.deenwallet.user.User;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionService {

    private static final Duration PAYMENT_CODE_TTL = Duration.ofMinutes(10);
    /** A user's history screen shows at most this many of their newest transactions. */
    private static final int MAX_USER_TRANSACTIONS = 200;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final TransactionRepository transactionRepository;
    private final MonimeClient monimeClient;
    private final SmsSender smsSender;
    private final MonimeProperties monimeProperties;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final BankTransferService bankTransferService;
    private final SavedRecipientRepository savedRecipientRepository;
    private final ProviderConfig providerConfig;
    private final PayoutRecoveryActionRepository recoveryActions;

    // ---- Abuse / velocity limits. SET THESE TO YOUR REAL COMPLIANCE LIMITS BEFORE LAUNCH. ----
    private static final int MAX_PENDING_PER_USER = 3;
    private static final int MAX_INITIATIONS_PER_HOUR = 20;
    private static final long MAX_DAILY_AMOUNT_MINOR = 20_000_000L; // SLE 200,000.00 per rolling 24h
    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(120);
    /** Extra time after the 10-minute code lifetime before an unpaid row may be cancelled. */
    private static final Duration EXPIRY_GRACE = Duration.ofMinutes(5);
    /** Per-user striped locks: close the double-tap race on a single instance. */
    private static final Object[] USER_LOCKS = new Object[64];
    static { for (int i = 0; i < USER_LOCKS.length; i++) USER_LOCKS[i] = new Object(); }
    private static Object userLock(UUID id) { return USER_LOCKS[id.hashCode() & 63]; }

    // ==============================================================
    // INITIATION GUARDS (account state, providers, spam, velocity, double-tap)
    // ==============================================================
    private void requireUsableUser(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!user.isActive()) throw new IllegalStateException("Account is inactive. Please contact support.");
        if (user.isLocked()) throw new IllegalStateException("Account is locked. Please contact support.");
        if (!user.isEmailVerified()) throw new IllegalStateException("Please verify your email first.");
    }

    private void requireKnownProvider(String providerId) {
        var prefixes = providerConfig.getPrefixes();
        if (providerId == null || prefixes == null || !prefixes.containsKey(providerId)) {
            throw new IllegalArgumentException("Unsupported mobile money provider.");
        }
    }

    /**
     * Applies velocity limits and double-tap protection, then inserts the row, all under a
     * per-user lock and one short DB transaction. If an identical unpaid request was created
     * moments ago, that existing row is returned (it already has a payment code) instead of
     * creating a second charge.
     */
    private Transaction saveWithGuards(UUID userId, long minorUnits, String sourcePhone,
                                       TransactionServiceType type, String destPhone, String bankAccount,
                                       Supplier<Transaction> newRow) {
        synchronized (userLock(userId)) {
            return transactionTemplate.execute(status -> {
                Instant now = Instant.now();
                List<Transaction> dups = transactionRepository.findRecentDuplicates(
                        userId, now.minus(DUPLICATE_WINDOW), minorUnits, sourcePhone, type,
                        destPhone == null ? "" : destPhone, bankAccount == null ? "" : bankAccount);
                if (!dups.isEmpty()) {
                    Transaction existing = dups.get(0);
                    if (existing.getMonimePaymentCodeId() == null) {
                        throw new TooManyRequestsException("Your previous request is still being processed. Please wait a moment.");
                    }
                    return existing; // idempotent replay: caller sees a payment code and returns it
                }
                if (transactionRepository.countByUserIdAndStatus(userId, TransactionStatus.AWAITING_PAYMENT) >= MAX_PENDING_PER_USER) {
                    throw new TooManyRequestsException("You have too many unpaid transfers. Complete them or wait up to 15 minutes for them to expire.");
                }
                if (transactionRepository.countByUserIdSince(userId, now.minus(Duration.ofHours(1))) >= MAX_INITIATIONS_PER_HOUR) {
                    throw new TooManyRequestsException("Too many transfer attempts. Please try again later.");
                }
                if (transactionRepository.sumAmountByUserIdSince(userId, now.minus(Duration.ofHours(24))) + minorUnits > MAX_DAILY_AMOUNT_MINOR) {
                    throw new IllegalArgumentException("Daily transfer limit reached. Please try again tomorrow.");
                }
                return transactionRepository.save(newRow.get());
            });
        }
    }

    // ==============================================================
    // ✅ GENERATE TRANSACTION CODE
    // ==============================================================
    public String generateTransactionCode() {
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 8 chars: with 5 chars (60M/day) the unique constraint would eventually collide
        // and fail a real user's transaction; 8 chars makes that practically impossible.
        String random = generateRandomString(8);
        return "DW-" + date + "-" + random;
    }

    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    // ==============================================================
    // ✅ INITIATE TRANSACTION - FIXED (Saves only ONCE)
    // ==============================================================
    // Deliberately NOT @Transactional. The two Monime calls are slow network I/O; wrapping
    // them in one transaction held a DB connection (pool of 30) for the whole round trip,
    // so a slow Monime could starve login, listing and polling for everyone.
    // Each database step below is its own short transaction.
    public TransactionResponse initiate(UUID userId, InitiateTransactionRequest request, String idempotencyKey) {
        requireUsableUser(userId);
        requireKnownProvider(request.getSourceProviderId());
        requireKnownProvider(request.getDestinationProviderId());

        // 1) Provider name lookup: network only, no DB connection held.
        ProviderKycResult kyc = monimeClient.getProviderKyc(
                request.getDestinationProviderId(), request.getDestinationPhone());

        long minorUnits = toMinorUnits(request.getAmount());
        ConversionFee fee = ConversionFee.calculate(minorUnits);
        Money collectAmount = new Money("SLE", fee.totalChargedValue());
        String transactionCode = generateTransactionCode();

        // 2) Short transaction: persist the row (its id is assigned here).
        Transaction saved = saveWithGuards(userId, minorUnits, request.getSourcePhone(),
                TransactionServiceType.MOBILE_MONEY, request.getDestinationPhone(), null, () ->
                Transaction.builder()
                        .userId(userId)
                        .recipientId(request.getRecipientId())
                        .amountCurrency("SLE")
                        .amountValue(minorUnits)
                        .feeValue(fee.feeValue())
                        .monimeDepositFeeValue(fee.monimeDepositFeeValue())
                        .deenWalletFeeValue(fee.deenWalletFeeValue())
                        .monimeWithdrawalFeeValue(fee.monimeWithdrawalFeeValue())
                        .totalChargedValue(fee.totalChargedValue())
                        .sourceProviderId(request.getSourceProviderId())
                        .sourcePhone(request.getSourcePhone())
                        .serviceType(TransactionServiceType.MOBILE_MONEY)
                        .destinationProviderId(request.getDestinationProviderId())
                        .destinationPhone(request.getDestinationPhone())
                        .destinationHolderName(kyc != null && kyc.account() != null ? kyc.account().holderName() : null)
                        .status(TransactionStatus.AWAITING_PAYMENT)
                        .smsSent(false)
                        .transactionCode(transactionCode)
                        .build());
        if (saved.getMonimePaymentCodeId() != null) return TransactionResponse.from(saved); // idempotent replay
        final UUID transactionId = saved.getId();

        // 3) Payment code creation: network only. If it fails, remove the row so a failed
        //    initiation leaves nothing behind (same outcome as the old single transaction).
        final PaymentCodeResult paymentCode;
        try {
            paymentCode = monimeClient.createPaymentCode(CreatePaymentCodeRequest.oneTime(
                    "Deen Wallet conversion",
                    collectAmount,
                    request.getSourcePhone(),
                    transactionId.toString()), "paycode-" + transactionId);
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> transactionRepository.deleteById(transactionId));
            throw e;
        }

        // 4) Short transaction: attach the payment code to the row.
        Transaction result = transactionTemplate.execute(status -> {
            Transaction t = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new IllegalStateException("Transaction disappeared during initiation"));
            if (paymentCode != null) {
                t.setMonimePaymentCodeId(paymentCode.id());
                t.setMonimeUssdCode(paymentCode.ussdCode());
            }
            return t; // dirty-checked and flushed at commit
        });

        return TransactionResponse.from(result);
    }

    /**
     * Starts a Bank Transfer using the same funding/payment-code mechanism as the
     * existing Mobile Money conversion flow. The bank payout is deliberately NOT
     * created here; it is created only after Monime reports payment_code.processed.
     */
    public TransactionResponse initiateBankTransfer(UUID userId, BankTransferRequest request) {
        requireUsableUser(userId);
        requireKnownProvider(request.getSourceProviderId());
        String accountNumber = BankTransferService.normalizeAccountNumber(request.getBankAccountNumber());
        var bank = bankTransferService.findAvailableBank(request.getBankProviderId());
        String holderName;
        boolean kycVerified;

        if (request.getRecipientId() != null) {
            SavedRecipient recipient = savedRecipientRepository.findById(request.getRecipientId())
                    .orElseThrow(() -> new IllegalArgumentException("Bank recipient not found."));
            if (!recipient.getUserId().equals(userId)) {
                throw new IllegalArgumentException("You do not have access to this bank recipient.");
            }
            if (recipient.getRecipientType() != SavedRecipientType.BANK) {
                throw new IllegalArgumentException("The selected recipient is not a bank recipient.");
            }
            if (!recipient.isBankKycVerified()
                    || recipient.getBankProviderId() == null
                    || !recipient.getBankProviderId().equals(bank.providerId())
                    || recipient.getBankAccountNumber() == null
                    || !recipient.getBankAccountNumber().equals(accountNumber)
                    || recipient.getBankHolderName() == null
                    || recipient.getBankHolderName().isBlank()) {
                throw new IllegalArgumentException("The selected bank recipient is no longer valid. Please add it again.");
            }
            holderName = recipient.getBankHolderName();
            kycVerified = true;
        } else {
            var verification = bankTransferService.verifyAccount(userId, bank.providerId(), accountNumber);
            if (!verification.verified()) {
                throw new IllegalArgumentException(verification.message());
            }
            holderName = verification.holderName();
            kycVerified = verification.verified();
        }

        long minorUnits = toMinorUnits(request.getAmount());
        ConversionFee fee = ConversionFee.calculate(minorUnits);
        Money collectAmount = new Money("SLE", fee.totalChargedValue());
        String transactionCode = generateTransactionCode();

        Transaction saved = saveWithGuards(userId, minorUnits, request.getSourcePhone(),
                TransactionServiceType.BANK_TRANSFER, null, accountNumber, () ->
                Transaction.builder()
                        .userId(userId)
                        .recipientId(request.getRecipientId())
                        .amountCurrency("SLE")
                        .amountValue(minorUnits)
                        .feeValue(fee.feeValue())
                        .monimeDepositFeeValue(fee.monimeDepositFeeValue())
                        .deenWalletFeeValue(fee.deenWalletFeeValue())
                        .monimeWithdrawalFeeValue(fee.monimeWithdrawalFeeValue())
                        .totalChargedValue(fee.totalChargedValue())
                        .sourceProviderId(request.getSourceProviderId())
                        .sourcePhone(request.getSourcePhone())
                        .serviceType(TransactionServiceType.BANK_TRANSFER)
                        .destinationProviderId(null)
                        .destinationPhone(null)
                        .destinationBankProviderId(bank.providerId())
                        .destinationBankName(bank.name())
                        .destinationBankAccountNumber(accountNumber)
                        .destinationBankHolderName(holderName)
                        .destinationBankKycVerified(kycVerified)
                        .destinationHolderName(holderName)
                        .status(TransactionStatus.AWAITING_PAYMENT)
                        .smsSent(false)
                        .transactionCode(transactionCode)
                        .build());
        if (saved.getMonimePaymentCodeId() != null) return TransactionResponse.from(saved); // idempotent replay
        final UUID transactionId = saved.getId();

        final PaymentCodeResult paymentCode;
        try {
            // Monime rejects a payment code that sets BOTH authorizedProviders and
            // authorizedPhoneNumber ("A payment provider must not be set when a paying
            // phone number is specified"). The phone number alone already identifies the
            // payer, so use the same phone-only request as the mobile-money flow.
            paymentCode = monimeClient.createPaymentCode(CreatePaymentCodeRequest.oneTime(
                    "Deen Wallet bank transfer",
                    collectAmount,
                    request.getSourcePhone(),
                    transactionId.toString()), "paycode-" + transactionId);
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(status -> transactionRepository.deleteById(transactionId));
            throw e;
        }

        Transaction result = transactionTemplate.execute(status -> {
            Transaction t = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new IllegalStateException("Transaction disappeared during initiation"));
            if (paymentCode == null || paymentCode.id() == null || paymentCode.id().isBlank()) {
                throw new IllegalStateException("Monime did not return a payment code.");
            }
            t.setMonimePaymentCodeId(paymentCode.id());
            t.setMonimeUssdCode(paymentCode.ussdCode());
            return t;
        });

        return TransactionResponse.from(result);
    }

    public List<Transaction> listForUser(UUID userId) {
        return transactionRepository.findRecentByUserId(userId, PageRequest.of(0, MAX_USER_TRANSACTIONS));
    }

    public Transaction getForUser(UUID userId, UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));

        if (!transaction.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You do not have access to this transaction");
        }

        return transaction;
    }

    private long toMinorUnits(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ONE) < 0 || amount.scale() > 2) throw new IllegalArgumentException("Amount must be at least 1.00 and have at most 2 decimal places");
        try { return amount.movePointRight(2).longValueExact(); } catch (ArithmeticException e) { throw new IllegalArgumentException("Amount must have at most 2 decimal places"); }
    }

    /**
     * NEVER cancels on the clock alone. A payer may complete the USSD prompt at minute 9:59 while
     * the webhook is delayed or lost; cancelling then would keep their money with no payout.
     * Before cancelling, Monime's payment code is asked what really happened. If it was paid, the
     * payout is resumed. If Monime cannot be reached, nothing is cancelled and we retry next run.
     * Not @Transactional on purpose: Monime calls must not hold a DB connection.
     */
    public void expireStalePendingTransactions() {
        Instant now = Instant.now();
        Instant cutoff = now.minus(PAYMENT_CODE_TTL).minus(EXPIRY_GRACE);
        for (Transaction t : transactionRepository.findByStatusAndCreatedAtBefore(
                TransactionStatus.AWAITING_PAYMENT, cutoff, PageRequest.of(0, 50))) {
            try {
                reconcileAwaitingPayment(t.getId(), t.getMonimePaymentCodeId(), t.getCreatedAt());
            } catch (Exception e) {
                log.warn("Could not reconcile AWAITING_PAYMENT transaction {}: {}", t.getTransactionCode(), e.toString());
            }
        }
        resumeInterruptedPayouts(now);
        // Money collected but the payout step was interrupted (e.g. Monime outage): resume it.
        for (Transaction t : transactionRepository.findByStatusAndCreatedAtBefore(
                TransactionStatus.PAID_IN, now.minusSeconds(120), PageRequest.of(0, 50))) {
            if (t.getMonimePayoutId() == null && t.getMonimePaymentCodeId() != null) {
                final String codeId = t.getMonimePaymentCodeId();
                try {
                    transactionTemplate.executeWithoutResult(st -> handlePaymentCodeProcessed(codeId));
                } catch (Exception e) {
                    log.warn("Could not resume payout for PAID_IN transaction {}: {}", t.getTransactionCode(), e.toString());
                }
            }
        }
    }

    /** An admin resend that was interrupted (timeout / crash) leaves PAYING_OUT with no payout id. Same key => safe retry. */
    private void resumeInterruptedPayouts(Instant now) {
        for (Transaction t : transactionRepository.findByStatusAndCreatedAtBefore(
                TransactionStatus.PAYING_OUT, now.minusSeconds(120), PageRequest.of(0, 50))) {
            if (t.getMonimePayoutId() != null) continue;
            final UUID id = t.getId();
            try {
                transactionTemplate.executeWithoutResult(st -> {
                    Transaction locked = transactionRepository.findByIdForUpdate(id).orElse(null);
                    if (locked == null || locked.getStatus() != TransactionStatus.PAYING_OUT
                            || locked.getMonimePayoutId() != null) return;
                    CreatePayoutRequest req = buildPayoutRequest(locked);
                    if (req == null) { moveToPayoutFailed(locked, "Destination account details are missing."); return; }
                    submitPayout(locked, req);
                });
            } catch (Exception e) {
                log.warn("Could not resume interrupted payout for {}: {}", t.getTransactionCode(), e.toString());
            }
        }
    }

    private void reconcileAwaitingPayment(UUID transactionId, String paymentCodeId, Instant createdAt) {
        if (paymentCodeId == null || paymentCodeId.isBlank()) {
            cancelIfStillAwaiting(transactionId, "The payment code could not be created. No money was taken.");
            return;
        }
        PaymentCodeResult code;
        try {
            code = monimeClient.getPaymentCode(paymentCodeId);
        } catch (org.springframework.web.client.RestClientResponseException
                 | org.springframework.web.client.ResourceAccessException e) {
            log.warn("Monime unreachable while checking payment code {}; will retry", paymentCodeId);
            return; // unknown => never cancel
        }
        String status = code == null || code.status() == null ? "" : code.status().toLowerCase();
        if (status.equals("processed")) {
            log.warn("Recovered missed payment_code.processed webhook for payment code {}", paymentCodeId);
            transactionTemplate.executeWithoutResult(st -> handlePaymentCodeProcessed(paymentCodeId));
        } else if (status.equals("expired") || status.equals("cancelled") || status.equals("canceled") || status.equals("failed")) {
            cancelIfStillAwaiting(transactionId, "Payment was not received before the payment window expired.");
        } else if (createdAt != null && createdAt.isBefore(Instant.now().minus(Duration.ofMinutes(60)))) {
            // Still not paid an hour after a 10-minute code was issued: it can no longer be paid.
            cancelIfStillAwaiting(transactionId, "Payment was not received before the payment window expired.");
        }
    }

    private void cancelIfStillAwaiting(UUID transactionId, String reason) {
        transactionTemplate.executeWithoutResult(st -> {
            Transaction t = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
            if (t == null || t.getStatus() != TransactionStatus.AWAITING_PAYMENT) return; // re-check under lock
            t.setStatus(TransactionStatus.CANCELED);
            t.setFailureReason(reason);
            transactionRepository.save(t);
            notificationService.create(t.getUserId(), "TRANSACTION_CANCELED", "Transfer canceled",
                    reason, t.getId(), t.getTransactionCode());
            pushNotificationService.sendToUser(t.getUserId(), "Transfer canceled", reason,
                    Map.of("type", "TRANSACTION_CANCELED", "transactionId", t.getId().toString()));
            userRepository.findById(t.getUserId())
                    .ifPresent(u -> emailService.sendTransactionFailedEmail(u.getEmail(), t));
            log.info("Canceled unpaid transaction {}", t.getTransactionCode());
        });
    }

    @Transactional
    public void handlePaymentCodeProcessed(String paymentCodeId) {
        Optional<Transaction> maybeTransaction =
                transactionRepository.findByMonimePaymentCodeIdForUpdate(paymentCodeId);

        if (maybeTransaction.isEmpty()) {
            log.warn("Received payment_code.processed for unknown payment code {}", paymentCodeId);
            return;
        }

        Transaction transaction = maybeTransaction.get();

        TransactionStatus current = transaction.getStatus();
        // A payer can legitimately pay AFTER we cancelled/failed the row (late USSD, delayed webhook).
        // Their money is in; it must be paid out, never ignored.
        boolean latePayment = current == TransactionStatus.CANCELED
                || (current == TransactionStatus.FAILED
                    && transaction.getPaidInAt() == null
                    && transaction.getMonimePayoutId() == null);
        boolean resumable = current == TransactionStatus.AWAITING_PAYMENT
                || current == TransactionStatus.PAID_IN
                || latePayment;
        if (!resumable) {
            log.info("Ignoring duplicate payment_code.processed for transaction {} (already in status {})",
                    transaction.getId(), current);
            return;
        }
        if (current == TransactionStatus.PAID_IN && transaction.getMonimePayoutId() != null) {
            return;
        }
        if (latePayment) {
            log.warn("Late payment received for {} transaction {}: recovering and paying out",
                    current, transaction.getTransactionCode());
            transaction.setFailureReason(null);
        }
        if (transaction.getPaidInAt() == null) {
            transaction.setPaidInAt(Instant.now());
        }
        if (current != TransactionStatus.PAID_IN) {
            transaction.setStatus(TransactionStatus.PAID_IN);
            transactionRepository.saveAndFlush(transaction);
        }

        // The payout is idempotent (key payout-{transactionId}), so resuming is always safe.
        CreatePayoutRequest payoutRequest;
        if (transaction.getServiceType() == TransactionServiceType.BANK_TRANSFER) {
            String accountNumber = transaction.getDestinationBankAccountNumber();
            if (accountNumber == null || accountNumber.isBlank()) {
                moveToPayoutFailed(transaction, "The bank account details were missing when the funding payment was received.");
                return;
            }
            payoutRequest = CreatePayoutRequest.toBank(
                    new Money(transaction.getAmountCurrency(), transaction.getAmountValue()),
                    transaction.getDestinationBankProviderId(), accountNumber,
                    monimeProperties.defaultFinancialAccountId());
        } else {
            payoutRequest = CreatePayoutRequest.toMobileMoney(
                    new Money(transaction.getAmountCurrency(), transaction.getAmountValue()),
                    transaction.getDestinationProviderId(), transaction.getDestinationPhone(),
                    monimeProperties.defaultFinancialAccountId());
        }
        submitPayout(transaction, payoutRequest);
    }

    /**
     * Shared by mobile-money and bank payouts. Ambiguous outcomes (timeouts, 5xx, 429, empty body)
     * are rethrown so the webhook stays retryable: the idempotency key makes the retry safe, and
     * we must NOT refund while the payout might still have been accepted (that would pay twice).
     * Only a definite rejection moves the money to the refund path.
     */
    private void submitPayout(Transaction transaction, CreatePayoutRequest payoutRequest) {
        PayoutResult payout;
        try {
            payout = monimeClient.createPayout(payoutRequest, payoutKey(transaction));
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.error("Payout request timed out for transaction {}", transaction.getTransactionCode(), e);
            throw e;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status == 408 || status == 409 || status >= 500) {
                log.error("Transient Monime payout failure for transaction {}: status={}",
                        transaction.getTransactionCode(), status, e);
                throw e;
            }
            log.error("Monime rejected payout for transaction {}: status={} body={}",
                    transaction.getTransactionCode(), status, e.getResponseBodyAsString());
            moveToPayoutFailed(transaction, "The recipient payout was rejected by the payment provider.");
            return;
        }

        if (payout == null || payout.id() == null || payout.id().isBlank()) {
            // Ambiguous: the payout may exist. Retry with the same idempotency key.
            throw new IllegalStateException("Payment provider returned no payout id; will retry.");
        }

        transaction.setMonimePayoutId(payout.id());
        if ("failed".equalsIgnoreCase(payout.status())) {
            String reason = payout.failureDetail() != null && payout.failureDetail().message() != null
                    ? payout.failureDetail().message()
                    : "The recipient payout was rejected or could not be completed by the payment provider.";
            moveToPayoutFailed(transaction, reason);
            return;
        }
        transaction.setStatus(TransactionStatus.PAYING_OUT);
        transactionRepository.save(transaction);
    }

    /**
     * Money is in but cannot be delivered. NO automatic refund: the row waits in PAYOUT_FAILED for an
     * admin (Failed Payouts section) to resend or refund it. The failure is recorded, the customer is
     * told why by in-app notification and email (first failure only, not on every admin resend), and an
     * ALERT line is logged. Runs inside the caller's transaction.
     */
    private void moveToPayoutFailed(Transaction transaction, String reason) {
        String clean = normalizeFailureReason(reason, "The transfer could not be completed.");
        boolean firstFailure = transaction.getPayoutAttempt() == 0;
        transaction.setStatus(TransactionStatus.PAYOUT_FAILED);
        transaction.setFailureReason(clean);
        transaction.setPayoutFailedAt(Instant.now());
        transactionRepository.save(transaction);
        recordAction(transaction.getId(), firstFailure ? "PAYOUT_FAILED" : "RESEND_FAILED", null, null, clean);
        log.error("ALERT payout failed for transaction {} ({}), awaiting admin: {}",
                transaction.getTransactionCode(), transaction.getId(), clean);

        if (firstFailure) {
            String msg = "We couldn't deliver transfer " + transaction.getTransactionCode()
                    + ". Your money is safe. Contact support with this number and we will retry or refund it.";
            notificationService.create(transaction.getUserId(), "TRANSACTION_PAYOUT_FAILED",
                    "Transfer delayed", msg, transaction.getId(), transaction.getTransactionCode());
            pushNotificationService.sendToUser(transaction.getUserId(), "Transfer delayed", msg,
                    Map.of("type", "TRANSACTION_PAYOUT_FAILED", "transactionId", transaction.getId().toString()));
            userRepository.findById(transaction.getUserId()).ifPresent(u ->
                    emailService.sendPayoutFailedEmail(u.getEmail(), transaction.getTransactionCode(),
                            display(transaction.getAmountValue()), display(transaction.getTotalChargedValue()),
                            recipientLabel(transaction), clean, transaction.getSourcePhone()));
        }
    }

    private void recordAction(UUID transactionId, String action, UUID actorId, String actorEmail, String detail) {
        recoveryActions.save(PayoutRecoveryAction.builder()
                .transactionId(transactionId).action(action).actorId(actorId).actorEmail(actorEmail)
                .detail(detail == null ? null : (detail.length() > 1000 ? detail.substring(0, 1000) : detail))
                .build());
    }

    private static String display(Long minorUnits) {
        return BigDecimal.valueOf(minorUnits == null ? 0 : minorUnits).movePointLeft(2).toPlainString();
    }

    private static String mask(String phone) {
        return phone == null || phone.length() < 4 ? phone : phone.substring(0, phone.length() - 4) + "****";
    }

    private static String recipientLabel(Transaction t) {
        if (t.getServiceType() == TransactionServiceType.BANK_TRANSFER) {
            return (t.getDestinationBankName() == null ? "the selected bank" : t.getDestinationBankName())
                    + " account " + mask(t.getDestinationBankAccountNumber());
        }
        return mask(t.getDestinationPhone());
    }

    /** First attempt keeps the original key; each admin resend uses a new one (the old key would replay the failed payout). */
    private static String payoutKey(Transaction t) {
        return t.getPayoutAttempt() == 0 ? "payout-" + t.getId() : "payout-" + t.getId() + "-" + t.getPayoutAttempt();
    }

    private CreatePayoutRequest buildPayoutRequest(Transaction t) {
        Money amount = new Money(t.getAmountCurrency(), t.getAmountValue());
        if (t.getServiceType() == TransactionServiceType.BANK_TRANSFER) {
            String acct = t.getDestinationBankAccountNumber();
            if (acct == null || acct.isBlank()) return null;
            return CreatePayoutRequest.toBank(amount, t.getDestinationBankProviderId(), acct,
                    monimeProperties.defaultFinancialAccountId());
        }
        return CreatePayoutRequest.toMobileMoney(amount, t.getDestinationProviderId(), t.getDestinationPhone(),
                monimeProperties.defaultFinancialAccountId());
    }

    private void notifyTransactionFailure(Transaction transaction) {
        notificationService.create(
                transaction.getUserId(),
                "TRANSACTION_FAILED",
                "Transfer failed",
                transaction.getFailureReason() == null ? "The transfer could not be completed." : transaction.getFailureReason(),
                transaction.getId(),
                transaction.getTransactionCode()
        );

        pushNotificationService.sendToUser(
                transaction.getUserId(),
                "Transfer failed",
                transaction.getFailureReason() == null ? "The transfer could not be completed." : transaction.getFailureReason(),
                Map.of("type", "TRANSACTION_FAILED", "transactionId", transaction.getId().toString())
        );

        userRepository.findById(transaction.getUserId())
                .ifPresent(user -> emailService.sendTransactionFailedEmail(user.getEmail(), transaction));
    }

    @Transactional
    public void handlePayoutCompleted(String payoutId) {
        Optional<Transaction> maybeTransaction =
                transactionRepository.findByMonimePayoutIdForUpdate(payoutId);

        if (maybeTransaction.isEmpty()) {
            log.warn("Received payout.completed for unknown payout {}", payoutId);
            return;
        }

        Transaction transaction = maybeTransaction.get();

        if (transaction.getStatus() != TransactionStatus.PAYING_OUT) {
            log.info("Ignoring duplicate payout.completed for transaction {} (already in status {})",
                    transaction.getId(), transaction.getStatus());
            return;
        }

        transaction.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(transaction);

        BigDecimal displayAmount = BigDecimal.valueOf(transaction.getAmountValue()).movePointLeft(2);
        boolean bankTransfer = transaction.getServiceType() == TransactionServiceType.BANK_TRANSFER;

        if (!bankTransfer && transaction.getDestinationPhone() != null) {
            String smsMessage = "You have received " + transaction.getAmountCurrency() + " "
                    + displayAmount + " via Deen Wallet.";
            smsSender.send(transaction.getDestinationPhone(), smsMessage);
            transaction.setSmsSent(true);
            transactionRepository.save(transaction);
        }

        String completionMessage = bankTransfer
                ? "Your bank transfer of " + transaction.getAmountCurrency() + " " + displayAmount + " was completed successfully."
                : "Your transfer of " + transaction.getAmountCurrency() + " " + displayAmount + " was completed successfully.";

        notificationService.create(
                transaction.getUserId(),
                "TRANSACTION_COMPLETED",
                "Transfer complete",
                completionMessage,
                transaction.getId(),
                transaction.getTransactionCode()
        );

        pushNotificationService.sendToUser(
                transaction.getUserId(),
                "Transfer complete",
                completionMessage,
                Map.of("type", "TRANSACTION_COMPLETED", "transactionId", transaction.getId().toString())
        );

        userRepository.findById(transaction.getUserId())
                .ifPresent(user -> emailService.sendTransactionCompletedEmail(user.getEmail(), transaction));
    }

    @Transactional
    public void handlePaymentCodeFailed(String paymentCodeId, String reason) {
        Optional<Transaction> maybeTransaction = transactionRepository.findByMonimePaymentCodeIdForUpdate(paymentCodeId);
        if (maybeTransaction.isEmpty()) return;
        Transaction transaction = maybeTransaction.get();
        if (transaction.getStatus() != TransactionStatus.AWAITING_PAYMENT) return;

        String failureReason = normalizeFailureReason(
                reason,
                "The payment code could not be completed by the payment provider."
        );

        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(failureReason);
        transactionRepository.save(transaction);

        notificationService.create(
                transaction.getUserId(),
                "TRANSACTION_FAILED",
                "Transfer failed",
                failureReason,
                transaction.getId(),
                transaction.getTransactionCode()
        );

        pushNotificationService.sendToUser(
                transaction.getUserId(),
                "Transfer failed",
                failureReason,
                Map.of(
                        "type", "TRANSACTION_FAILED",
                        "transactionId", transaction.getId().toString()
                )
        );

        userRepository.findById(transaction.getUserId())
                .ifPresent(user -> emailService.sendTransactionFailedEmail(user.getEmail(), transaction));
    }

    @Transactional
    public void handlePayoutFailed(String payoutId) {
        handlePayoutFailed(payoutId, null);
    }

    @Transactional
    public void handlePayoutFailed(String payoutId, String reason) {
        Optional<Transaction> maybeTransaction =
                transactionRepository.findByMonimePayoutIdForUpdate(payoutId);

        if (maybeTransaction.isEmpty()) {
            log.warn("Received payout.failed for unknown payout {}", payoutId);
            return;
        }

        Transaction transaction = maybeTransaction.get();

        if (transaction.getStatus() != TransactionStatus.PAYING_OUT
                && transaction.getStatus() != TransactionStatus.PAID_IN) {
            log.info("Ignoring duplicate payout.failed for transaction {} (already in status {})",
                    transaction.getId(), transaction.getStatus());
            return;
        }

        moveToPayoutFailed(transaction, reason);
    }

    // ==============================================================
    // REFUNDS: only ever started by an admin (requestRefund). RefundScheduler just finishes them.
    // Fixed idempotency key refund-{id} means a crash or timeout can never produce a second refund;
    // a refund the provider rejects goes to NEEDS_REVIEW (human).
    // ==============================================================
    public void processRefund(UUID transactionId) {
        Transaction t = transactionRepository.findById(transactionId).orElse(null);
        if (t == null || t.getStatus() != TransactionStatus.REFUND_PENDING) return;

        if (t.getRefundPayoutId() != null) {
            resolveRefundPayout(t);
            return;
        }

        CreatePayoutRequest request = CreatePayoutRequest.toMobileMoney(
                new Money(t.getAmountCurrency(), t.getTotalChargedValue()),
                t.getSourceProviderId(), t.getSourcePhone(),
                monimeProperties.defaultFinancialAccountId());
        PayoutResult payout;
        try {
            payout = monimeClient.createPayout(request, "refund-" + t.getId());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("Refund request timed out for {}; will retry", t.getTransactionCode());
            return;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status == 408 || status == 409 || status >= 500) {
                log.warn("Transient refund failure for {} (HTTP {}); will retry", t.getTransactionCode(), status);
                return;
            }
            markNeedsReview(t.getId(), "Refund rejected by provider (HTTP " + status + "): " + e.getResponseBodyAsString());
            return;
        }
        if (payout == null || payout.id() == null || payout.id().isBlank()) {
            log.warn("Refund for {} returned no payout id; will retry with the same key", t.getTransactionCode());
            return;
        }
        final String refundPayoutId = payout.id();
        transactionTemplate.executeWithoutResult(st ->
                transactionRepository.findByIdForUpdate(transactionId).ifPresent(x -> {
                    if (x.getStatus() == TransactionStatus.REFUND_PENDING && x.getRefundPayoutId() == null) {
                        x.setRefundPayoutId(refundPayoutId);
                        transactionRepository.save(x);
                    }
                }));
        // The refund payout may already be terminal.
        transactionRepository.findById(transactionId).ifPresent(this::resolveRefundPayout);
    }

    private void resolveRefundPayout(Transaction t) {
        if (t.getRefundPayoutId() == null || t.getStatus() != TransactionStatus.REFUND_PENDING) return;
        PayoutResult p;
        try {
            p = monimeClient.getPayout(t.getRefundPayoutId());
        } catch (org.springframework.web.client.RestClientResponseException
                 | org.springframework.web.client.ResourceAccessException e) {
            return; // try again next run
        }
        if (p == null || p.status() == null) return;
        String st = p.status().toLowerCase();
        if (st.equals("completed")) {
            transactionTemplate.executeWithoutResult(x ->
                    transactionRepository.findByIdForUpdate(t.getId()).ifPresent(tx -> {
                        if (tx.getStatus() != TransactionStatus.REFUND_PENDING) return;
                        tx.setStatus(TransactionStatus.REFUNDED);
                        transactionRepository.save(tx);
                        recordAction(tx.getId(), "REFUND_SENT", null, null, "Refund payout " + tx.getRefundPayoutId() + " completed");
                        notifyUser(tx, "TRANSACTION_REFUNDED", "Refund sent",
                                "Your money for transfer " + tx.getTransactionCode() + " has been refunded.");
                        userRepository.findById(tx.getUserId()).ifPresent(u ->
                                emailService.sendRefundCompletedEmail(u.getEmail(), tx.getTransactionCode(),
                                        display(tx.getTotalChargedValue()), tx.getSourcePhone()));
                    }));
        } else if (st.equals("failed") || st.equals("rejected") || st.equals("canceled") || st.equals("cancelled")) {
            markNeedsReview(t.getId(), "Refund payout " + t.getRefundPayoutId() + " ended as " + st);
        }
    }

    private void markNeedsReview(UUID transactionId, String detail) {
        transactionTemplate.executeWithoutResult(x ->
                transactionRepository.findByIdForUpdate(transactionId).ifPresent(tx -> {
                    if (tx.getStatus() != TransactionStatus.REFUND_PENDING) return;
                    tx.setStatus(TransactionStatus.NEEDS_REVIEW);
                    tx.setFailureReason("Your refund is being reviewed by our support team. Your money is safe.");
                    transactionRepository.save(tx);
                    recordAction(tx.getId(), "REFUND_FAILED", null, null, detail);
                    log.error("ALERT MANUAL REFUND REQUIRED for transaction {} ({}): {}",
                            tx.getTransactionCode(), tx.getId(), detail);
                    notifyUser(tx, "TRANSACTION_REFUND_REVIEW", "Refund under review",
                            "Your refund for transfer " + tx.getTransactionCode()
                                    + " is being reviewed by support. Your money is safe.");
                }));
    }

    private void notifyUser(Transaction t, String type, String title, String message) {
        notificationService.create(t.getUserId(), type, title, message, t.getId(), t.getTransactionCode());
        pushNotificationService.sendToUser(t.getUserId(), title, message,
                Map.of("type", type, "transactionId", t.getId().toString()));
    }

    /**
     * Reconciles payouts when the webhook connection is interrupted. Monime's payout
     * resource is authoritative; this is intentionally sparse (only PAYING_OUT rows
     * older than two minutes) so it does not create continuous Monime traffic.
     */
    public void reconcilePayout(String payoutId) {
        // Do not hold a database transaction while calling Monime. The reconciliation
        // request can be slow during an outage; the final state update methods below
        // acquire their own short transaction/row lock.
        Optional<Transaction> maybeTransaction =
                transactionRepository.findByMonimePayoutId(payoutId);
        if (maybeTransaction.isEmpty()) return;

        Transaction transaction = maybeTransaction.get();
        if (transaction.getStatus() != TransactionStatus.PAYING_OUT) return;

        PayoutResult payout;
        try {
            payout = monimeClient.getPayout(payoutId);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            log.warn("Could not reconcile payout {} for transaction {}: HTTP {}",
                    payoutId, transaction.getTransactionCode(), e.getStatusCode().value());
            return;
        } catch (org.springframework.web.client.ResourceAccessException e) {
            log.warn("Could not reconcile payout {} for transaction {}: connection failure",
                    payoutId, transaction.getTransactionCode());
            return;
        }

        if (payout == null || payout.status() == null) return;

        if ("completed".equalsIgnoreCase(payout.status())) {
            transactionTemplate.executeWithoutResult(st -> handlePayoutCompleted(payoutId));
        } else if ("failed".equalsIgnoreCase(payout.status())
                || "rejected".equalsIgnoreCase(payout.status())
                || "canceled".equalsIgnoreCase(payout.status())) {
            String reason = payout.failureDetail() != null ? payout.failureDetail().message() : null;
            transactionTemplate.executeWithoutResult(st -> handlePayoutFailed(payoutId, reason));
        }
    }

    // ==============================================================
    // ADMIN: FAILED PAYOUT RECOVERY (resend / refund). Called by AdminPayoutController.
    // ==============================================================

    /**
     * Before resending or refunding, make sure the earlier payout really did fail at Monime. Our own
     * PAYOUT_FAILED can come from a webhook; paying the recipient AND refunding the sender would be a
     * double spend. If Monime cannot be reached, refuse: a human can try again in a minute.
     */
    private void assertPreviousPayoutFailed(Transaction t) {
        if (t.getMonimePayoutId() == null) return; // never created at Monime (definite rejection)
        PayoutResult p;
        try {
            p = monimeClient.getPayout(t.getMonimePayoutId());
        } catch (org.springframework.web.client.RestClientResponseException
                 | org.springframework.web.client.ResourceAccessException e) {
            throw new IllegalStateException("Could not verify the earlier payout with the payment provider. Please try again in a minute.");
        }
        String st = p == null || p.status() == null ? "unknown" : p.status().toLowerCase();
        if (!(st.equals("failed") || st.equals("rejected") || st.equals("canceled") || st.equals("cancelled"))) {
            throw new IllegalStateException("The earlier payout is not failed at the payment provider (status: "
                    + st + "). Nothing was changed, to avoid paying twice.");
        }
    }

    /** Admin button "Send again": retry the payout to the recipient with a fresh idempotency key. */
    public Transaction adminResendPayout(UUID transactionId, UUID adminId, String adminEmail) {
        Transaction current = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        if (current.getStatus() != TransactionStatus.PAYOUT_FAILED) {
            throw new IllegalStateException("Only a failed payout can be sent again (current status: " + current.getStatus() + ").");
        }
        assertPreviousPayoutFailed(current);

        // Step 1: claim under a row lock. A second click or second admin loses the race here.
        transactionTemplate.executeWithoutResult(st -> {
            Transaction t = transactionRepository.findByIdForUpdate(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            if (t.getStatus() != TransactionStatus.PAYOUT_FAILED) {
                throw new IllegalStateException("This payout was already handled (status: " + t.getStatus() + ").");
            }
            String previous = t.getMonimePayoutId();
            t.setPayoutAttempt(t.getPayoutAttempt() + 1);
            t.setStatus(TransactionStatus.PAYING_OUT);
            t.setMonimePayoutId(null);
            t.setFailureReason(null);
            transactionRepository.save(t);
            recordAction(transactionId, "RESEND", adminId, adminEmail,
                    "Attempt " + t.getPayoutAttempt() + (previous != null ? "; previous payout " + previous : ""));
        });

        // Step 2: submit. Definite rejection => back to PAYOUT_FAILED (recorded, customer not re-emailed).
        // Ambiguous failure => stays PAYING_OUT; resumeInterruptedPayouts retries with the SAME key.
        try {
            transactionTemplate.executeWithoutResult(st -> {
                Transaction t = transactionRepository.findByIdForUpdate(transactionId).orElseThrow();
                if (t.getStatus() != TransactionStatus.PAYING_OUT || t.getMonimePayoutId() != null) return;
                CreatePayoutRequest req = buildPayoutRequest(t);
                if (req == null) { moveToPayoutFailed(t, "Destination account details are missing."); return; }
                submitPayout(t, req);
            });
        } catch (RuntimeException e) {
            log.warn("Resend of {} is pending (provider did not answer clearly): {}", transactionId, e.toString());
        }
        return transactionRepository.findById(transactionId).orElseThrow();
    }

    /** Admin button "Refund": send the money back to the number that paid. Never automatic. */
    public Transaction adminRequestRefund(UUID transactionId, UUID adminId, String adminEmail, String note) {
        Transaction current = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
        if (current.getStatus() != TransactionStatus.PAYOUT_FAILED) {
            throw new IllegalStateException("Only a failed payout can be refunded (current status: " + current.getStatus() + ").");
        }
        assertPreviousPayoutFailed(current);

        transactionTemplate.executeWithoutResult(st -> {
            Transaction t = transactionRepository.findByIdForUpdate(transactionId)
                    .orElseThrow(() -> new IllegalArgumentException("Transaction not found"));
            if (t.getStatus() != TransactionStatus.PAYOUT_FAILED) {
                throw new IllegalStateException("This payout was already handled (status: " + t.getStatus() + ").");
            }
            t.setStatus(TransactionStatus.REFUND_PENDING);
            transactionRepository.save(t);
            recordAction(transactionId, "REFUND", adminId, adminEmail,
                    "Refund of SLE " + display(t.getTotalChargedValue()) + " to " + mask(t.getSourcePhone())
                            + (note == null || note.isBlank() ? "" : "; note: " + note.trim()));
        });

        Transaction t = transactionRepository.findById(transactionId).orElseThrow();
        userRepository.findById(t.getUserId()).ifPresent(u ->
                emailService.sendRefundInitiatedEmail(u.getEmail(), t.getTransactionCode(),
                        display(t.getTotalChargedValue()), t.getSourcePhone()));
        try {
            processRefund(transactionId);
        } catch (RuntimeException e) {
            log.warn("Refund for {} will be completed by the scheduler: {}", transactionId, e.toString());
        }
        return transactionRepository.findById(transactionId).orElseThrow();
    }

    public List<PayoutRecoveryAction> recoveryHistory(UUID transactionId) {
        return recoveryActions.findByTransactionIdOrderByCreatedAtDesc(transactionId);
    }

    private String normalizeFailureReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        String clean = reason.trim().replaceAll("\\s+", " ");
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

}