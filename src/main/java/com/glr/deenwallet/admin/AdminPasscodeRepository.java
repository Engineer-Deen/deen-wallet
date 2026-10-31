package com.glr.deenwallet.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminPasscodeRepository extends JpaRepository<AdminPasscode, UUID> {

    List<AdminPasscode> findByAdminIdOrderByCreatedAtAsc(UUID adminId);

    long countByAdminId(UUID adminId);

    Optional<AdminPasscode> findByIdAndAdminId(UUID id, UUID adminId);
}
