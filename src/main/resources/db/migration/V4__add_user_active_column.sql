
-- V4__add_user_active_column.sql
-- Adds active flag to users table for admin deactivation

ALTER TABLE users
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- Index for filtering active users
CREATE INDEX idx_users_active ON users (active);