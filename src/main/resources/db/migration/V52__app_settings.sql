-- Small key/value settings the admin can change from the admin app (for example the support WhatsApp number).
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key   VARCHAR(60)  PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
