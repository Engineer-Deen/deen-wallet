-- One row per day with only totals (no customer details). The nightly retention job deletes old
-- transactions, so this small table keeps the business history (count, volume, fees earned) that the
-- admin Home page shows for the month. Filled by DailyStatsJob before the wipe runs.
CREATE TABLE IF NOT EXISTS daily_stats (
    day           DATE        PRIMARY KEY,
    tx_count      BIGINT      NOT NULL DEFAULT 0,
    volume_value  BIGINT      NOT NULL DEFAULT 0,
    fees_value    BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
