package com.glr.deenwallet.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface BiometricChallengeRepository extends JpaRepository<BiometricChallenge, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BiometricChallenge> findByChallengeAndUsedFalse(String challenge);
}
