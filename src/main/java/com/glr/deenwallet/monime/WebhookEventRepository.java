package com.glr.deenwallet.monime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    boolean existsByEventKey(String eventKey);

    /** Idempotency keys only matter while a provider might still redeliver. */
    @Transactional
    @Modifying
    @Query("DELETE FROM WebhookEvent w WHERE w.createdAt < :cutoff")
    int purgeOlderThan(@Param("cutoff") Instant cutoff);
}
