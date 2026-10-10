package com.glr.deenwallet.recipient;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "saved_recipients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SavedRecipient {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 20)
    @Builder.Default
    private SavedRecipientType recipientType = SavedRecipientType.MOBILE_MONEY;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "holder_name")
    private String holderName;

    @Column(name = "bank_provider_id", length = 64)
    private String bankProviderId;

    @Column(name = "bank_name", length = 200)
    private String bankName;

    @Column(name = "bank_account_number", length = 64)
    private String bankAccountNumber;

    @Column(name = "bank_holder_name", length = 200)
    private String bankHolderName;

    @Column(name = "bank_kyc_verified", nullable = false)
    @Builder.Default
    private boolean bankKycVerified = false;

    private String label;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
