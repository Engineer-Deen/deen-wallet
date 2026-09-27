package com.glr.deenwallet.monime;
import jakarta.persistence.*; import lombok.*; import java.time.Instant; import java.util.UUID;
@Entity @Table(name="webhook_events") @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor public class WebhookEvent { @Id @GeneratedValue private UUID id; @Column(name="event_key",nullable=false,unique=true,length=128) private String eventKey; @Column(name="event_name",nullable=false,length=100) private String eventName; @Column(name="resource_id",nullable=false,length=128) private String resourceId; @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt; @PrePersist void create(){if(createdAt==null)createdAt=Instant.now();} }

