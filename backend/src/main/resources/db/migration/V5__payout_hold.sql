-- FR-PAY-9 / FR-ADMIN-4: an admin must be able to HOLD a contractor payout (dispute, quality issue,
-- compliance problem) and release it later. The transfer row already tracks the payout itself.

ALTER TABLE transfers ADD COLUMN hold_reason text;
ALTER TABLE transfers ADD COLUMN held_by uuid REFERENCES profiles(id);
ALTER TABLE transfers ADD COLUMN held_at timestamptz;

-- A job-level hold flag so the payout endpoint can refuse before a transfer row exists.
ALTER TABLE jobs ADD COLUMN payout_hold_reason text;
