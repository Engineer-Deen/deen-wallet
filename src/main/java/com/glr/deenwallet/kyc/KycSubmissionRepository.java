package com.glr.deenwallet.kyc;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    /** The customer's current DRAFT or PENDING submission (at most one: unique index). */
    @Query("SELECT s FROM KycSubmission s WHERE s.userId = :userId "
            + "AND s.status IN (com.glr.deenwallet.kyc.KycStatus.DRAFT, com.glr.deenwallet.kyc.KycStatus.PENDING)")
    Optional<KycSubmission> findOpenByUserId(@Param("userId") UUID userId);

    /** Same, but locks the row so two requests (double tap) cannot change it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM KycSubmission s WHERE s.userId = :userId "
            + "AND s.status IN (com.glr.deenwallet.kyc.KycStatus.DRAFT, com.glr.deenwallet.kyc.KycStatus.PENDING)")
    Optional<KycSubmission> findOpenByUserIdForUpdate(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM KycSubmission s WHERE s.id = :id")
    Optional<KycSubmission> findByIdForUpdate(@Param("id") UUID id);

    Optional<KycSubmission> findByUserIdAndStatus(UUID userId, KycStatus status);

    boolean existsByUserIdAndStatus(UUID userId, KycStatus status);

    /** Newest first. Used to show the latest outcome (for example the rejection reason). */
    List<KycSubmission> findTop3ByUserIdOrderByCreatedAtDesc(UUID userId);

    /** How many submissions this customer sent since the given moment (abuse guard). */
    long countByUserIdAndSubmittedAtAfter(UUID userId, Instant since);

    /** Is this document already verified on ANOTHER account? */
    boolean existsByDocumentNumberHashAndStatusAndUserIdNot(String hash, KycStatus status, UUID userId);

    /** How many other accounts have this same document waiting or verified (shown to the admin). */
    long countByDocumentNumberHashAndUserIdNotAndStatusIn(String hash, UUID userId, Collection<KycStatus> statuses);

    long countByStatus(KycStatus status);

    /** Review queue, oldest first. */
    List<KycSubmission> findByStatusOrderBySubmittedAtAsc(KycStatus status, Pageable pageable);

    /** Recently decided or open submissions, newest activity first. */
    List<KycSubmission> findByStatusInOrderByUpdatedAtDesc(Collection<KycStatus> statuses, Pageable pageable);

    /** Newest history of one customer, for the admin detail view. */
    List<KycSubmission> findTop10ByUserIdOrderByCreatedAtDesc(UUID userId);
}
