package com.glr.deenwallet.transaction;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue
    private UUID id;

    // ==============================================================
    // ✅ HUMAN-READABLE TRANSACTION CODE
    // Format: DW-YYYYMMDD-XXXXX (e.g., DW-20260905-A7B3C)
    // ==============================================================
    @Column(name = "transaction_code", nullable = false, unique = true)
    private String transactionCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "recipient_id")
    private UUID recipientId;

    @Column(name = "amount_currency", nullable = false)
    private String amountCurrency;

    @Column(name = "amount_value", nullable = false)
    private Long amountValue;

    @Column(name = "fee_value", nullable = false)
    private Long feeValue;

    @Column(name = "monime_deposit_fee_value", nullable = false)
    private Long monimeDepositFeeValue;

    @Column(name = "deen_wallet_fee_value", nullable = false)
    private Long deenWalletFeeValue;

    @Column(name = "monime_withdrawal_fee_value", nullable = false)
    private Long monimeWithdrawalFeeValue;

    @Column(name = "total_charged_value", nullable = false)
    private Long totalChargedValue;

    @Column(name = "source_provider_id", nullable = false)
    private String sourceProviderId;

    @Column(name = "source_phone", nullable = false)
    private String sourcePhone;

    @Column(name = "destination_provider_id", nullable = false)
    private String destinationProviderId;

    @Column(name = "destination_phone", nullable = false)
    private String destinationPhone;

    @Column(name = "destination_holder_name")
    private String destinationHolderName;

    @Column(name = "monime_payment_code_id")
    private String monimePaymentCodeId;

    @Column(name = "monime_ussd_code")
    private String monimeUssdCode;

    @Column(name = "monime_payout_id")
    private String monimePayoutId;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "sms_sent", nullable = false)
    private boolean smsSent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = TransactionStatus.AWAITING_PAYMENT;
        }
        // ==============================================================
        // ✅ AUTO-GENERATE TRANSACTION CODE
        // ==============================================================
        if (transactionCode == null || transactionCode.isBlank()) {
            transactionCode = generateTransactionCode();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // ==============================================================
    // ✅ GENERATE TRANSACTION CODE
    // Format: DW-YYYYMMDD-XXXXX
    // Example: DW-20260905-A7B3C
    // ==============================================================
    private String generateTransactionCode() {
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 8 chars, matching TransactionService's generator: 5 chars (60M/day) could
        // eventually collide with the unique constraint. This @PrePersist path is only
        // a safety net (TransactionService always sets the code before save), but it
        // must use the same length or it reintroduces the exact bug being fixed.
        String random = generateRandomString(8);
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
}
