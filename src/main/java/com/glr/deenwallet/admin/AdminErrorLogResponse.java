package com.glr.deenwallet.admin;
import com.glr.deenwallet.monitoring.ErrorLog;
import java.time.Instant;
import java.util.UUID;
public record AdminErrorLogResponse(UUID id, UUID userId, String errorType, String message, Integer statusCode, String url, String userAgent, String actionBuffer, String stack, Integer line, Integer col, Instant createdAt) {
    public static AdminErrorLogResponse from(ErrorLog e){ return new AdminErrorLogResponse(e.getId(),e.getUserId(),e.getErrorType(),e.getMessage(),e.getStatusCode(),e.getUrl(),e.getUserAgent(),e.getActionBuffer(),e.getStack(),e.getLine(),e.getCol(),e.getCreatedAt()); }
}
