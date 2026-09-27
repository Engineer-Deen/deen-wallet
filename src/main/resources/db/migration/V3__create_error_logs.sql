
-- V3__create_error_logs.sql
-- Error-only monitoring: stores JS errors, failed API calls (4xx/5xx),
-- and rage-click events reported by the frontend, along with a short
-- buffer of the user's recent actions leading up to it. Nothing is
-- stored unless an actual error/rage-click occurred. Rows older than
-- 30 days are purged by a scheduled job (see ErrorLogCleanupJob).

CREATE TABLE error_logs (
                            id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            user_id         UUID,
                            error_type      VARCHAR(30)     NOT NULL,
                            message         VARCHAR(2000),
                            status_code     INT,
                            url             VARCHAR(500),
                            user_agent      VARCHAR(500),
                            action_buffer   VARCHAR(4000),
                            created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

-- Supports the daily cleanup job's "older than 30 days" query.
CREATE INDEX idx_error_logs_created_at ON error_logs (created_at);
CREATE INDEX idx_error_logs_user_id ON error_logs (user_id);
