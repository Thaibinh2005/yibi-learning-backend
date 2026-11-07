-- Forgot password: only a hash of the emailed token is kept, so a database leak gives no working link.
CREATE TABLE password_reset_tokens (
    id          BIGSERIAL PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens(user_id);

-- Google sign-in: the account's stable Google id, since an email address can change.
ALTER TABLE users ADD COLUMN google_sub VARCHAR(255);
CREATE UNIQUE INDEX uq_users_google_sub ON users(google_sub) WHERE google_sub IS NOT NULL;
