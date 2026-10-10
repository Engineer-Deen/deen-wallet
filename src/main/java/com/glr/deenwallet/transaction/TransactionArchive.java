package com.glr.deenwallet.transaction;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Same shape as {@link Transaction}. A row here means the original transaction was
 * moved out of the hot `transactions` table because it is old and in a final state -
 * nothing is deleted, see TransactionArchiveJob and the RUNBOOK for the retention policy.
 */
@Entity
@Table(name = "transactions_archive")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TransactionArchive {

    @Id
    private UUID id;

    @Column(name = "transaction_code", nullable = false)
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

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 30)
    private TransactionServiceType serviceType;

    @Column(name = "destination_provider_id")
    private String destinationProviderId;

    @Column(name = "destination_phone")
    private String destinationPhone;

    @Column(name = "destination_bank_provider_id", length = 64)
    private String destinationBankProviderId;

    @Column(name = "destination_bank_name", length = 200)
    private String destinationBankName;

    @Column(name = "destination_bank_account_number", length = 64)
    private String destinationBankAccountNumber;

    @Column(name = "destination_bank_holder_name", length = 200)
    private String destinationBankHolderName;

    @Column(name = "destination_bank_kyc_verified", nullable = false)
    private boolean destinationBankKycVerified;

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

    @Column(nullable = false)
    private String status;

    @Column(name = "sms_sent", nullable = false)
    private boolean smsSent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "archived_at", nullable = false)
    private Instant archivedAt;

    public static TransactionArchive from(Transaction t) {
        return TransactionArchive.builder()
                .id(t.getId())
                .transactionCode(t.getTransactionCode())
                .userId(t.getUserId())
                .recipientId(t.getRecipientId())
                .amountCurrency(t.getAmountCurrency())
                .amountValue(t.getAmountValue())
                .feeValue(t.getFeeValue())
                .monimeDepositFeeValue(t.getMonimeDepositFeeValue())
                .deenWalletFeeValue(t.getDeenWalletFeeValue())
                .monimeWithdrawalFeeValue(t.getMonimeWithdrawalFeeValue())
                .totalChargedValue(t.getTotalChargedValue())
                .sourceProviderId(t.getSourceProviderId())
                .sourcePhone(t.getSourcePhone())
                .serviceType(t.getServiceType())
                .destinationProviderId(t.getDestinationProviderId())
                .destinationPhone(t.getDestinationPhone())
                .destinationBankProviderId(t.getDestinationBankProviderId())
                .destinationBankName(t.getDestinationBankName())
                .destinationBankAccountNumber(t.getDestinationBankAccountNumber())
                .destinationBankHolderName(t.getDestinationBankHolderName())
                .destinationBankKycVerified(t.isDestinationBankKycVerified())
                .destinationHolderName(t.getDestinationHolderName())
                .monimePaymentCodeId(t.getMonimePaymentCodeId())
                .monimeUssdCode(t.getMonimeUssdCode())
                .monimePayoutId(t.getMonimePayoutId())
                .failureReason(t.getFailureReason())
                .status(t.getStatus().name())
                .smsSent(t.isSmsSent())
                .createdAt(t.getCreatedAt())
                .updatedAt(t.getUpdatedAt())
                .archivedAt(Instant.now())
                .build();
    }
}
