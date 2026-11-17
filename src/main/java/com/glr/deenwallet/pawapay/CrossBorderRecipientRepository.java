package com.glr.deenwallet.pawapay;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CrossBorderRecipientRepository extends JpaRepository<CrossBorderRecipient, UUID> {

    List<CrossBorderRecipient> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<CrossBorderRecipient> findByUserIdAndProviderAndPhone(UUID userId, String provider, String phone);

    long countByUserId(UUID userId);
}
