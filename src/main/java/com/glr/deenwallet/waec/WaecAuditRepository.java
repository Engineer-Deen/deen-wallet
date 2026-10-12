package com.glr.deenwallet.waec;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WaecAuditRepository extends JpaRepository<WaecAudit, UUID> {
}
