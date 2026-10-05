package com.glr.deenwallet.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BiometricChallengeRepository extends JpaRepository<BiometricChallenge, UUID> {
    Optional<BiometricChallenge> findByChallengeAndUsedFalse(String challenge);
}
