-- Audit log: one line per thing an admin did (who, what, on whom, when, from where).
-- KYC history is read straight from kyc_audit; WAEC and payout-recovery lines are copied in here
-- by AuditMirrorJob before the data-retention job removes them.
CREATE TABLE IF NOT EXISTS admin_audit (
    id          UUID PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    category    VARCHAR(20)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    summary     VARCHAR(200) NOT NULL,
    actor_id    UUID,
    actor_name  VARCHAR(200),
    actor_email VARCHAR(254),
    target_id   UUID,
    target_name VARCHAR(200),
    detail      VARCHAR(500),
    ip          VARCHAR(64),
    status_code INT
);
CREATE INDEX IF NOT EXISTS idx_admin_audit_created  ON admin_audit (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_actor    ON admin_audit (actor_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_admin_audit_category ON admin_audit (category, created_at DESC);
