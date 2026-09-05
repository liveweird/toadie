ALTER TABLE users ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

-- Ephemeral authentication state, not a business entity: logout deletes the whole family.
-- Tokens issued before this migration have no session id and must sign in again.
CREATE TABLE auth_sessions (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_version BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_auth_sessions_expires_at ON auth_sessions(expires_at);
CREATE INDEX idx_auth_sessions_user_id ON auth_sessions(user_id);
