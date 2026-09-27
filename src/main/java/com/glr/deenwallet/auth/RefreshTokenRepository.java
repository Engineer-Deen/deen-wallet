package com.glr.deenwallet.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByJti(String jti);
}