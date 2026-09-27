package com.glr.deenwallet.monitoring;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "error_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ErrorLog {
    @Id @GeneratedValue private UUID id;
    @Column(name="user_id") private UUID userId;
    @Column(name="error_type", nullable=false) private String errorType;
    private String message;
    @Column(name="status_code") private Integer statusCode;
    private String url;
    @Column(name="user_agent") private String userAgent;
    @Column(name="action_buffer") private String actionBuffer;
    @Column(name="stack") private String stack;
    @Column(name="line") private Integer line;
    @Column(name="col") private Integer col;
    @Column(name="created_at", nullable=false, updatable=false) private Instant createdAt;
    @PrePersist protected void onCreate(){ createdAt=Instant.now(); }
}
