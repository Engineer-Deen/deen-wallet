package com.glr.deenwallet.monime; import org.springframework.data.jpa.repository.JpaRepository; import java.util.UUID; public interface WebhookEventRepository extends JpaRepository<WebhookEvent,UUID>{ boolean existsByEventKey(String eventKey); }

