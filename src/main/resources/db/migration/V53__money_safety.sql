-- One row: the emergency freeze switch for all money movement (payouts, refunds, withdrawals, new transfers).
CREATE TABLE IF NOT EXISTS money_safety (
    id         SMALLINT     PRIMARY KEY,
    frozen     BOOLEAN      NOT NULL DEFAULT FALSE,
    reason     VARCHAR(300),
    changed_by UUID,
    changed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO money_safety (id, frozen) VALUES (1, FALSE) ON CONFLICT (id) DO NOTHING;
