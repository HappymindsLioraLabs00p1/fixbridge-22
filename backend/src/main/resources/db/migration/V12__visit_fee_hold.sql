-- The hold placed on a homeowner's card for the contractor's visit fee.
--
-- Recorded on the job rather than in the payments table because it is not a payment yet: nothing
-- has been taken. It becomes a payment only if a contractor accepts and the hold is captured.
--
-- authorized_cents is stored alongside the intent id so capture uses the figure the homeowner was
-- actually shown. Recalculating at capture time would let a rate change between acceptance and
-- dispatch produce a charge nobody agreed to.

ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS visit_fee_intent_id        text,
    ADD COLUMN IF NOT EXISTS visit_fee_authorized_cents bigint,
    ADD COLUMN IF NOT EXISTS visit_fee_captured_at      timestamptz;

COMMENT ON COLUMN jobs.visit_fee_intent_id IS
    'Stripe PaymentIntent holding the contractor visit fee. Null once released or never authorised.';
COMMENT ON COLUMN jobs.visit_fee_authorized_cents IS
    'The amount the homeowner saw and agreed to. Capture never exceeds this.';
COMMENT ON COLUMN jobs.visit_fee_captured_at IS
    'Set when a contractor accepted and the hold was taken. Null while merely reserved.';
