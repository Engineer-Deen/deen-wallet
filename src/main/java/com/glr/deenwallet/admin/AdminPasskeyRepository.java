package com.glr.deenwallet.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminPasskeyRepository extends JpaRepository<AdminPasskey, UUID> {

    List<AdminPasskey> findByAdminIdOrderByCreatedAtAsc(UUID adminId);

    long countByAdminId(UUID adminId);

    Optional<AdminPasskey> findByCredentialId(String credentialId);

    Optional<AdminPasskey> findByIdAndAdminId(UUID id, UUID adminId);
}
