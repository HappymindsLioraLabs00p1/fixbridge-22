-- FR-AUTH-5: password reset and email verification.
-- Tokens are stored HASHED (never in plaintext), are single-use and expire — the raw token only
-- ever exists in the email we send.

CREATE TYPE auth_token_purpose AS ENUM ('password_reset', 'email_verification');

CREATE TABLE auth_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  token_hash  text NOT NULL,
  purpose     auth_token_purpose NOT NULL,
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_auth_tokens_hash ON auth_tokens(token_hash);
CREATE INDEX idx_auth_tokens_user_purpose ON auth_tokens(user_id, purpose);
