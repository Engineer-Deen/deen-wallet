package com.glr.deenwallet.monitoring;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErrorLogRepository extends JpaRepository<ErrorLog, UUID> {

    // Was a derived delete: Hibernate SELECTed every matching row (stack traces, 4 KB
    // buffers) into memory and deleted them one by one. One bulk statement instead.
    @Transactional
    @Modifying
    @Query("DELETE FROM ErrorLog e WHERE e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);

    @Transactional
    @Modifying
    @Query("DELETE FROM ErrorLog e")
    int deleteAllErrors();

    @Query("SELECT e FROM ErrorLog e ORDER BY e.createdAt DESC")
    List<ErrorLog> findRecent(Pageable pageable);

    /** Live-poll query for the admin Errors tab: only rows newer than the last one it already has. */
    @Query("SELECT e FROM ErrorLog e WHERE e.createdAt > :since ORDER BY e.createdAt DESC")
    List<ErrorLog> findSince(@Param("since") Instant since, Pageable pageable);

    long countByCreatedAtAfter(Instant time);

    long countBySourceAppAndCreatedAtAfter(String sourceApp, Instant time);

    @Query("SELECT e.errorType, COUNT(e) FROM ErrorLog e GROUP BY e.errorType ORDER BY COUNT(e) DESC")
    List<Object[]> countGroupByErrorType();
}
