package com.glr.deenwallet.monitoring;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErrorLogRepository extends JpaRepository<ErrorLog, UUID> {
    @Transactional
    @Modifying
    void deleteByCreatedAtBefore(Instant cutoff);

    @Transactional
    @Modifying
    @Query("DELETE FROM ErrorLog e")
    int deleteAllErrors();

    @Query("SELECT e FROM ErrorLog e ORDER BY e.createdAt DESC")
    List<ErrorLog> findRecent(Pageable pageable);

    long countByCreatedAtAfter(Instant time);

    @Query("SELECT e.errorType, COUNT(e) FROM ErrorLog e GROUP BY e.errorType ORDER BY COUNT(e) DESC")
    List<Object[]> countGroupByErrorType();
}
