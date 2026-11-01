-- Profile photo of a customer or admin: one small square JPEG per account (re-encoded by the server).
CREATE TABLE IF NOT EXISTS user_photos (
    user_id    UUID        PRIMARY KEY,
    content    BYTEA       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
