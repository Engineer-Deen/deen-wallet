package com.glr.deenwallet.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") UUID userId);

    Optional<User> findByUsername(String username);
    Optional<User> findByPhone(String phone);
    Optional<User> findByAccountNumber(String accountNumber);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByPhone(String phone);
    boolean existsByAccountNumber(String accountNumber);
    List<User> findAllByOrderByCreatedAtDesc();
    long countByEmailVerifiedTrue();
    long countByPhoneVerifiedTrue();
    long countByActiveTrue();
    long countByLockedTrue();

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.locked = true, u.lockedAt = :lockedAt, u.lockedBy = :lockedBy WHERE u.id = :userId")
    int lockUserFast(@Param("userId") UUID userId, @Param("lockedAt") Instant lockedAt, @Param("lockedBy") UUID lockedBy);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.locked = false, u.lockedAt = null, u.lockedBy = null, u.pinAttempts = 0, u.loginFailedAttempts = 0, u.loginLockedUntil = null, u.lastLoginFailedAt = null, u.adminRecoveryRequired = false, u.active = true WHERE u.id = :userId AND u.locked = true")
    int unlockUserFast(@Param("userId") UUID userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.pinAttempts = 0 WHERE u.id = :userId")
    int resetPinAttempts(@Param("userId") UUID userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE User u SET
            u.pinAttempts = u.pinAttempts + 1,
            u.locked = CASE
                WHEN u.role IN ('ADMIN', 'SUPER_ADMIN') THEN u.locked
                WHEN u.pinAttempts + 1 >= 3 THEN true
                ELSE u.locked
            END,
            u.lockedAt = CASE
                WHEN u.role IN ('ADMIN', 'SUPER_ADMIN') THEN u.lockedAt
                WHEN u.pinAttempts + 1 >= 3 THEN :now
                ELSE u.lockedAt
            END
        WHERE u.id = :userId AND u.locked = false
        """)
    int recordFailedPinAttempt(@Param("userId") UUID userId, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.pinAttempts = 0 WHERE u.id = :userId")
    int clearFailedPinAttempts(@Param("userId") UUID userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.loginFailedAttempts = u.loginFailedAttempts + 1, u.lastLoginFailedAt = :now WHERE u.id = :userId")
    int incrementLoginFailures(@Param("userId") UUID userId, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE User u
        SET
            u.loginFailedAttempts = u.loginFailedAttempts + 1,
            u.lastLoginFailedAt = :now,
            u.loginLockedUntil = CASE
                WHEN u.loginFailedAttempts + 1 >= 5 THEN :lockedUntil
                ELSE u.loginLockedUntil
            END
        WHERE u.id = :userId
        """)
    int recordUserPasswordFailure(
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("lockedUntil") Instant lockedUntil
    );

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE User u
        SET
            u.loginFailedAttempts = u.loginFailedAttempts + 1,
            u.lastLoginFailedAt = :now,
            u.locked = CASE WHEN u.role = 'ADMIN' AND u.loginFailedAttempts + 1 >= 2 THEN true ELSE u.locked END,
            u.lockedAt = CASE WHEN u.role = 'ADMIN' AND u.loginFailedAttempts + 1 >= 2 THEN :now ELSE u.lockedAt END,
            u.adminRecoveryRequired = CASE WHEN u.role = 'SUPER_ADMIN' AND u.loginFailedAttempts + 1 >= 2 THEN true ELSE u.adminRecoveryRequired END
        WHERE u.id = :userId
          AND u.role IN ('ADMIN', 'SUPER_ADMIN')
          AND u.loginFailedAttempts < 2
        """)
    int recordAdminPasswordFailure(@Param("userId") UUID userId, @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.loginFailedAttempts = 0, u.loginLockedUntil = null, u.lastLoginFailedAt = null, u.adminRecoveryRequired = false WHERE u.id = :userId")
    int clearLoginFailures(@Param("userId") UUID userId);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.loginFailedAttempts = :attempts, u.adminRecoveryRequired = :required WHERE u.id = :userId")
    int setAdminRecoveryState(@Param("userId") UUID userId, @Param("attempts") int attempts, @Param("required") boolean required);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.loginFailedAttempts = 0, u.loginLockedUntil = null, u.lastLoginFailedAt = null, u.adminRecoveryRequired = false, u.locked = CASE WHEN u.role = 'ADMIN' THEN false ELSE u.locked END, u.lockedAt = CASE WHEN u.role = 'ADMIN' THEN null ELSE u.lockedAt END, u.lockedBy = CASE WHEN u.role = 'ADMIN' THEN null ELSE u.lockedBy END WHERE u.id = :userId")
    int resetLoginProtection(@Param("userId") UUID userId);
}
