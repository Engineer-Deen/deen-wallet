CREATE TABLE biometric_credentials (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id VARCHAR(200) NOT NULL UNIQUE,
    public_key TEXT NOT NULL,
    device_name VARCHAR(150),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_biometric_credentials_user_id ON biometric_credentials(user_id);
CREATE INDEX idx_biometric_credentials_credential_id ON biometric_credentials(credential_id);

CREATE TABLE biometric_challenges (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    credential_id VARCHAR(200),
    challenge VARCHAR(200) NOT NULL UNIQUE,
    purpose VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_biometric_challenges_challenge ON biometric_challenges(challenge);
CREATE INDEX idx_biometric_challenges_expires_at ON biometric_challenges(expires_at);
