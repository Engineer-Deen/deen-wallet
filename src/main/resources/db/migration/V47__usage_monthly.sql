-- Month-by-month usage of this server (kept so it is still there after restarts and when you start paying).
CREATE TABLE IF NOT EXISTS usage_monthly (
    month          CHAR(7)      PRIMARY KEY,   -- e.g. 2026-10
    tx_bytes       BIGINT       NOT NULL DEFAULT 0,   -- data sent out to the internet
    rx_bytes       BIGINT       NOT NULL DEFAULT 0,   -- data received
    disk_used      BIGINT       NOT NULL DEFAULT 0,   -- highest storage used this month
    disk_total     BIGINT       NOT NULL DEFAULT 0,
    ram_peak_pct   NUMERIC(5,1) NOT NULL DEFAULT 0,
    cpu_peak_pct   NUMERIC(5,1) NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
