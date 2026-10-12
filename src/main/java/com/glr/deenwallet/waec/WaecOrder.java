package com.glr.deenwallet.waec;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "waec_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WaecOrder {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "transaction_code", nullable = false, length = 60)
    private String transactionCode;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_value", nullable = false)
    private long unitPriceValue;

    @Column(name = "amount_value", nullable = false)
    private long amountValue;

    @Column(name = "fee_value", nullable = false)
    private long feeValue;

    @Column(name = "total_charged_value", nullable = false)
    private long totalChargedValue;

    @Column(name = "delivery_channel", nullable = false, length = 10)
    private String deliveryChannel;

    @Column(name = "delivery_email", length = 254)
    private String deliveryEmail;

    @Column(name = "delivery_phone", length = 32)
    private String deliveryPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 12)
    private WaecDeliveryStatus deliveryStatus;

    @Column(name = "delivery_attempts", nullable = false)
    private int deliveryAttempts;

    @Column(name = "last_delivery_error", length = 500)
    private String lastDeliveryError;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (deliveryStatus == null) deliveryStatus = WaecDeliveryStatus.PENDING;
        if (deliveryChannel == null) deliveryChannel = "EMAIL";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
