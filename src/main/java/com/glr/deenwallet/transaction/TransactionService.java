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
import com.glr.deenwallet.notification.PushNotificationService;
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
    public TransactionResponse initiate(UUID userId, InitiateTransactionRequest request) {
        // 1) Provider name lookup: network only, no DB connection held.
        ProviderKycResult kyc = monimeClient.getProviderKyc(
                request.getDestinationProviderId(), request.getDestinationPhone());

        long minorUnits = toMinorUnits(request.getAmount());
        ConversionFee fee = ConversionFee.calculate(minorUnits);
        Money collectAmount = new Money("SLE", fee.totalChargedValue());
        String transactionCode = generateTransactionCode();

        // 2) Short transaction: persist the row (its id is assigned here).
        Transaction saved = transactionTemplate.execute(status -> transactionRepository.save(
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
                        .destinationProviderId(request.getDestinationProviderId())
                        .destinationPhone(request.getDestinationPhone())
                        .destinationHolderName(kyc != null && kyc.account() != null ? kyc.account().holderName() : null)
                        .status(TransactionStatus.AWAITING_PAYMENT)
                        .smsSent(false)
                        .transactionCode(transactionCode)
                        .build()));
        final UUID transactionId = saved.getId();

        // 3) Payment code creation: network only. If it fails, remove the row so a failed
        //    initiation leaves nothing behind (same outcome as the old single transaction).
        final PaymentCodeResult paymentCode;
        try {
            paymentCode = monimeClient.createPaymentCode(CreatePaymentCodeRequest.oneTime(
                    "Deen Wallet conversion",
                    collectAmount,
                    request.getSourcePhone(),
                    transactionId.toString()));
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

    @Transactional
    public void expireStalePendingTransactions() {
        Instant cutoff = Instant.now().minus(PAYMENT_CODE_TTL);
        List<Transaction> stale = transactionRepository
                .findByStatusAndCreatedAtBefore(TransactionStatus.AWAITING_PAYMENT, cutoff);

        for (Transaction transaction : stale) {
            String cancellationReason =
                    "Payment was not received before the 10-minute payment window expired.";

            transaction.setStatus(TransactionStatus.CANCELED);
            transaction.setFailureReason(cancellationReason);
            transactionRepository.save(transaction);

            notificationService.create(
                    transaction.getUserId(),
                    "TRANSACTION_CANCELED",
                    "Transfer canceled",
                    cancellationReason,
                    transaction.getId(),
                    transaction.getTransactionCode()
            );

            pushNotificationService.sendToUser(
                    transaction.getUserId(),
                    "Transfer canceled",
                    cancellationReason,
                    Map.of(
                            "type", "TRANSACTION_CANCELED",
                            "transactionId", transaction.getId().toString()
                    )
            );

            userRepository.findById(transaction.getUserId())
                    .ifPresent(user -> emailService.sendTransactionFailedEmail(user.getEmail(), transaction));

            log.info("Canceled stale AWAITING_PAYMENT transaction {} (created at {})",
                    transaction.getTransactionCode(), transaction.getCreatedAt());
        }
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

        if (transaction.getStatus() != TransactionStatus.AWAITING_PAYMENT) {
            log.info("Ignoring duplicate payment_code.processed for transaction {} (already in status {})",
                    transaction.getId(), transaction.getStatus());
            return;
        }

        transaction.setStatus(TransactionStatus.PAID_IN);
        transactionRepository.saveAndFlush(transaction);

        Money payoutAmount = new Money(transaction.getAmountCurrency(), transaction.getAmountValue());

        CreatePayoutRequest payoutRequest = CreatePayoutRequest.toMobileMoney(
                payoutAmount,
                transaction.getDestinationProviderId(),
                transaction.getDestinationPhone(),
                monimeProperties.defaultFinancialAccountId()
        );

        String payoutIdempotencyKey = "payout-" + transaction.getId();

        PayoutResult payout = monimeClient.createPayout(
                payoutRequest,
                payoutIdempotencyKey
        );

        if (payout == null || payout.id() == null || payout.id().isBlank()) {
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setFailureReason("The payment was received, but the payout could not be created by the payment provider.");
            transactionRepository.save(transaction);
            userRepository.findById(transaction.getUserId())
                    .ifPresent(user -> emailService.sendTransactionFailedEmail(user.getEmail(), transaction));
            log.error("Payout creation failed for transaction {}: no payout ID returned", transaction.getTransactionCode());
            return;
        }

        transaction.setMonimePayoutId(payout.id());
        transaction.setStatus(TransactionStatus.PAYING_OUT);
        transactionRepository.save(transaction);
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
        String smsMessage = "You have received " + transaction.getAmountCurrency() + " "
                + displayAmount + " via Deen Wallet.";

        smsSender.send(transaction.getDestinationPhone(), smsMessage);

        transaction.setSmsSent(true);
        transactionRepository.save(transaction);

        // Persist the notification so it remains available in the user's
        // notification center even if FCM delivery is unavailable.
        notificationService.create(
                transaction.getUserId(),
                "TRANSACTION_COMPLETED",
                "Transfer complete",
                "Your transfer of " + transaction.getAmountCurrency() + " " + displayAmount + " was completed successfully.",
                transaction.getId(),
                transaction.getTransactionCode()
        );

        pushNotificationService.sendToUser(
                transaction.getUserId(),
                "Transfer complete",
                "Your transfer of " + transaction.getAmountCurrency() + " " + displayAmount + " was completed successfully.",
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

        if (transaction.getStatus() == TransactionStatus.FAILED
                || transaction.getStatus() == TransactionStatus.COMPLETED) {
            log.info("Ignoring duplicate payout.failed for transaction {} (already in status {})",
                    transaction.getId(), transaction.getStatus());
            return;
        }

        String failureReason = normalizeFailureReason(
                reason,
                "The recipient payout was rejected or could not be completed by the payment provider."
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
    private String normalizeFailureReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        String clean = reason.trim().replaceAll("\\s+", " ");
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

}
