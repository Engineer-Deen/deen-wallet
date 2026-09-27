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
import com.glr.deenwallet.notification.PushNotificationService;
import com.glr.deenwallet.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    private final TransactionRepository transactionRepository;
    private final MonimeClient monimeClient;
    private final SmsSender smsSender;
    private final MonimeProperties monimeProperties;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;

    // ==============================================================
    // ✅ GENERATE TRANSACTION CODE
    // ==============================================================
    public String generateTransactionCode() {
        String date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        String random = generateRandomString(5);
        return "DW-" + date + "-" + random;
    }

    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ==============================================================
    // ✅ INITIATE TRANSACTION - FIXED (Saves only ONCE)
    // ==============================================================
    @Transactional
    public TransactionResponse initiate(UUID userId, InitiateTransactionRequest request) {
        ProviderKycResult kyc = monimeClient.getProviderKyc(
                request.getDestinationProviderId(), request.getDestinationPhone());

        long minorUnits = toMinorUnits(request.getAmount());
        ConversionFee fee = ConversionFee.calculate(minorUnits);

        Money collectAmount = new Money("SLE", fee.totalChargedValue());

        // Generate transaction code
        String transactionCode = generateTransactionCode();

        Transaction transaction = Transaction.builder()
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
                .transactionCode(transactionCode)  // ✅ Set transaction code
                .build();

        // ✅ SAVE ONCE
        transaction = transactionRepository.save(transaction);

        CreatePaymentCodeRequest paymentCodeRequest = CreatePaymentCodeRequest.oneTime(
                "Deen Wallet conversion",
                collectAmount,
                request.getSourcePhone(),
                transaction.getId().toString()
        );

        PaymentCodeResult paymentCode = monimeClient.createPaymentCode(paymentCodeRequest);

        if (paymentCode != null) {
            transaction.setMonimePaymentCodeId(paymentCode.id());
            transaction.setMonimeUssdCode(paymentCode.ussdCode());
            // ✅ Hibernate auto-flush handles the update, NO SECOND SAVE needed
        }

        // ✅ Return WITHOUT another save
        return TransactionResponse.from(transaction);
    }

    public List<Transaction> listForUser(UUID userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId);
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
            transaction.setStatus(TransactionStatus.CANCELED);
            transaction.setFailureReason("Payment was not received before the 10-minute payment window expired.");
            transactionRepository.save(transaction);

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
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(normalizeFailureReason(reason, "The payment code could not be completed by the payment provider."));
        transactionRepository.save(transaction);
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

        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setFailureReason(normalizeFailureReason(reason, "The recipient payout was rejected or could not be completed by the payment provider."));
        transactionRepository.save(transaction);

        userRepository.findById(transaction.getUserId())
                .ifPresent(user -> emailService.sendTransactionFailedEmail(user.getEmail(), transaction));
    }
    private String normalizeFailureReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        String clean = reason.trim().replaceAll("\\s+", " ");
        return clean.length() > 1000 ? clean.substring(0, 1000) : clean;
    }

}
