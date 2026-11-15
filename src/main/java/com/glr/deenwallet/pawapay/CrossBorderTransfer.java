package com.glr.deenwallet.pawapay;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cross_border_transfers")
@Getter
@Setter
@NoArgsConstructor
public class CrossBorderTransfer {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CrossBorderStatus status;

    @Column(name = "send_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal sendAmount;
    @Column(name = "send_currency", nullable = false, length = 3)
    private String sendCurrency;
    @Column(name = "fee_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal feeAmount;
    @Column(name = "receive_amount", nullable = false, precision = 20, scale = 3)
    private BigDecimal receiveAmount;
    @Column(name = "receive_currency", nullable = false, length = 3)
    private String receiveCurrency;
    @Column(name = "fx_rate", nullable = false, precision = 24, scale = 8)
    private BigDecimal fxRate;

    @Column(name = "payer_provider", nullable = false, length = 40)
    private String payerProvider;
    @Column(name = "payer_phone", nullable = false, length = 20)
    private String payerPhone;
    @Column(name = "dest_country", nullable = false, length = 3)
    private String destCountry;
    @Column(name = "dest_provider", nullable = false, length = 40)
    private String destProvider;
    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String recipientPhone;
    @Column(name = "recipient_first_name", nullable = false, length = 64)
    private String recipientFirstName;
    @Column(name = "recipient_last_name", nullable = false, length = 64)
    private String recipientLastName;

    @Column(name = "deposit_id", nullable = false, unique = true)
    private UUID depositId;
    @Column(name = "remittance_id", nullable = false, unique = true)
    private UUID remittanceId;
    @Column(name = "refund_id", nullable = false, unique = true)
    private UUID refundId;

    @Column(name = "deposit_status", length = 24)
    private String depositStatus;
    @Column(name = "remittance_status", length = 24)
    private String remittanceStatus;
    @Column(name = "refund_status", length = 24)
    private String refundStatus;
    @Column(name = "deposit_provider_txn_id", length = 100)
    private String depositProviderTxnId;
    @Column(name = "remittance_provider_txn_id", length = 100)
    private String remittanceProviderTxnId;

    @Column(name = "sender_enc", nullable = false, columnDefinition = "TEXT")
    private String senderEnc;

    @Column(name = "failure_code", length = 64)
    private String failureCode;
    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(nullable = false)
    private int attempts;

    /** Sent by the app with every "send": a second tap of the same button finds this transfer instead of making a new one. */
    @Column(name = "client_request_id")
    private UUID clientRequestId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
