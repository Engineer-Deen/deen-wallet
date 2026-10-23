-- Identity (KYC) verification.
--  * kyc_submissions : one row per verification attempt (details + review result).
--  * kyc_documents   : the photos, stored ENCRYPTED (AES-256-GCM) as bytea in this database.
--  * kyc_audit       : who submitted / viewed / approved / rejected / revoked, and when. Never updated.
-- The document number is stored encrypted; document_number_hash is a keyed HMAC used only to stop the
-- same ID being verified on two accounts. No foreign keys to users/transactions on purpose (same as
-- the other audit-style tables): the trail must survive account or transaction clean-ups.

CREATE TABLE IF NOT EXISTS kyc_submissions (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL,
    status                VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',
    document_type         VARCHAR(20),
    full_name_on_document VARCHAR(120),
    document_number_enc   VARCHAR(600),
    document_number_hash  VARCHAR(64),
    document_last4        VARCHAR(4),
    date_of_birth         DATE,
    document_expiry_date  DATE,
    submitted_at          TIMESTAMPTZ,
    reviewed_at           TIMESTAMPTZ,
    reviewed_by           UUID,
    reviewed_by_email     VARCHAR(254),
    rejection_reason      VARCHAR(500),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_kyc_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    CONSTRAINT ck_kyc_document_type CHECK (document_type IS NULL
        OR document_type IN ('NATIONAL_ID', 'DRIVERS_LICENSE', 'PASSPORT'))
);

-- At most one open submission per customer, and at most one approved one.
CREATE UNIQUE INDEX IF NOT EXISTS uq_kyc_one_open_per_user
    ON kyc_submissions (user_id) WHERE status IN ('DRAFT', 'PENDING');
CREATE UNIQUE INDEX IF NOT EXISTS uq_kyc_one_approved_per_user
    ON kyc_submissions (user_id) WHERE status = 'APPROVED';
-- The same ID document can be verified on only one account.
CREATE UNIQUE INDEX IF NOT EXISTS uq_kyc_approved_document
    ON kyc_submissions (document_number_hash) WHERE status = 'APPROVED' AND document_number_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_kyc_user_created ON kyc_submissions (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kyc_status_submitted ON kyc_submissions (status, submitted_at);
CREATE INDEX IF NOT EXISTS idx_kyc_document_hash ON kyc_submissions (document_number_hash);

CREATE TABLE IF NOT EXISTS kyc_documents (
    id            UUID PRIMARY KEY,
    submission_id UUID         NOT NULL REFERENCES kyc_submissions (id) ON DELETE CASCADE,
    kind          VARCHAR(8)   NOT NULL,
    content_type  VARCHAR(20)  NOT NULL,
    size_bytes    INTEGER      NOT NULL,
    data          BYTEA        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_kyc_document_kind UNIQUE (submission_id, kind),
    CONSTRAINT ck_kyc_document_kind CHECK (kind IN ('FRONT', 'BACK', 'SELFIE'))
);

CREATE TABLE IF NOT EXISTS kyc_audit (
    id            UUID PRIMARY KEY,
    submission_id UUID,
    user_id       UUID         NOT NULL,
    action        VARCHAR(30)  NOT NULL,
    actor_id      UUID,
    actor_email   VARCHAR(254),
    detail        VARCHAR(500),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_kyc_audit_submission ON kyc_audit (submission_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kyc_audit_user ON kyc_audit (user_id, created_at DESC);
