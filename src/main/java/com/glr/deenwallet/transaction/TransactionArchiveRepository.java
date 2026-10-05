package com.glr.deenwallet.transaction;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionArchiveRepository extends JpaRepository<TransactionArchive, UUID> {

    /** Support/admin tracing: "where did my old transaction go". */
    Optional<TransactionArchive> findByTransactionCode(String transactionCode);

    @Query("SELECT a FROM TransactionArchive a WHERE a.userId = :userId ORDER BY a.createdAt DESC")
    List<TransactionArchive> findByUserId(@Param("userId") UUID userId, Pageable pageable);
}
