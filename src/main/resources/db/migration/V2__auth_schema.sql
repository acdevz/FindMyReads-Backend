-- =============================================================================
-- V2__auth_schema.sql
-- Auth additions — OAuth provider columns + refresh token store
-- =============================================================================

-- ── OAuth columns on users ────────────────────────────────────────────────────
-- password_hash is now nullable — OAuth users have no password.
-- oauth_provider + oauth_id identify the external account.
-- A user who registers via email AND later links Google gets one row —
-- email is the dedup key (matched on OAuth callback by provider email).

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS oauth_provider  TEXT,         -- 'google' | 'apple' | null
    ADD COLUMN IF NOT EXISTS oauth_id        TEXT,         -- provider's subject/sub claim
    ADD COLUMN IF NOT EXISTS avatar_url      TEXT,         -- profile pic from OAuth
    ADD COLUMN IF NOT EXISTS email_verified  BOOLEAN NOT NULL DEFAULT FALSE;

-- A user can only have one account per provider
CREATE UNIQUE INDEX IF NOT EXISTS users_oauth_provider_id_idx
    ON users (oauth_provider, oauth_id)
    WHERE oauth_provider IS NOT NULL;

-- ── Refresh tokens ────────────────────────────────────────────────────────────
-- One row per active session. Rotation: on every refresh, old token is deleted
-- and a new one is issued. Reuse of a revoked token = family revocation.

CREATE TABLE IF NOT EXISTS refresh_tokens (
                                id          UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
                                user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                token_hash  TEXT        NOT NULL UNIQUE,   -- SHA-256 of the raw token (never store raw)
                                expires_at  TIMESTAMPTZ NOT NULL,
                                created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                revoked     BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX refresh_tokens_user_idx    ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_hash_idx    ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_expiry_idx  ON refresh_tokens (expires_at)
    WHERE revoked = FALSE;               -- partial index — cleanup queries only scan active tokens