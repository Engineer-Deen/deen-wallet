CREATE TABLE pin_reset_challenges (
                                      id UUID PRIMARY KEY,
                                      user_id UUID NOT NULL,
                                      email VARCHAR(320) NOT NULL,
                                      otp_hash VARCHAR(255) NOT NULL,
                                      otp_expires_at TIMESTAMPTZ NOT NULL,
                                      otp_attempts INTEGER NOT NULL DEFAULT 0,
                                      otp_used BOOLEAN NOT NULL DEFAULT FALSE,
                                      reset_token_hash VARCHAR(255),
                                      reset_token_expires_at TIMESTAMPTZ,
                                      reset_token_used_at TIMESTAMPTZ,
                                      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      CONSTRAINT fk_pin_reset_challenges_user
                                          FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_pin_reset_challenges_email_created
    ON pin_reset_challenges(email, created_at);

CREATE INDEX idx_pin_reset_challenges_token
    ON pin_reset_challenges(reset_token_hash);
