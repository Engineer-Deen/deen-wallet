package com.glr.deenwallet.kyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycAuditRepository extends JpaRepository<KycAudit, UUID> {

    List<KycAudit> findTop40ByUserIdOrderByCreatedAtDesc(UUID userId);
}
