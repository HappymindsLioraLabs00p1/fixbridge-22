-- One-time codes for passwordless sign-in ("Continue with Phone" / "Continue with Email").
--
-- Codes are stored HASHED (BCrypt, like passwords) — a database read must not be enough to sign in
-- as someone else. The raw code exists only in the SMS/email and in memory while being checked.
--
-- The same table holds short-lived signup tickets: when a code verifies but no account matches the
-- destination, the client gets a ticket to carry into onboarding instead of tokens. purpose keeps
-- the two apart; a ticket cannot be replayed as a code or vice versa.
CREATE TABLE otp_codes (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  -- E.164 phone (+12125551234) or lower-cased email, normalised before storage so lookups match.
  destination  text NOT NULL,
  channel      text NOT NULL CHECK (channel IN ('sms', 'email')),
  purpose      text NOT NULL CHECK (purpose IN ('login', 'signup')),
  code_hash    text NOT NULL,
  expires_at   timestamptz NOT NULL,
  -- Wrong guesses against this code. Capped in code; the cap is what makes 6 digits safe.
  attempts     int NOT NULL DEFAULT 0,
  consumed_at  timestamptz,
  created_at   timestamptz NOT NULL DEFAULT now()
);

-- verify() wants "the latest live code for this destination"; send() wants "how many were sent
-- recently" for rate limiting. One index serves both.
CREATE INDEX idx_otp_codes_destination ON otp_codes (destination, purpose, created_at DESC);
