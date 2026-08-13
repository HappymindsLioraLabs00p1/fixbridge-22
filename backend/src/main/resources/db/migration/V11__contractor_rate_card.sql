-- A contractor's published rates.
--
-- Until now a contractor had one number, min_trip_charge_cents, which had to stand in for every
-- kind of call-out. A homeowner cannot be shown an honest visit fee before dispatch from a single
-- figure that means something different at 2am on a Sunday than it does on a Tuesday morning.
--
-- All nullable and all defaulted to zero. Nothing reads these yet, so an existing contractor is
-- unaffected until they fill them in, and a zero reads as "not charged" rather than "unknown".

ALTER TABLE contractors
    -- The standard diagnostic visit. This is fee B in the pricing model: separate from the
    -- FixBridge coordination fee, and never waived by a FixBridge promotion.
    ADD COLUMN IF NOT EXISTS visit_fee_cents            bigint NOT NULL DEFAULT 0,
    -- Surcharges, expressed as the total fee for that circumstance rather than an increment, so
    -- what the homeowner is quoted is what they authorise.
    ADD COLUMN IF NOT EXISTS emergency_fee_cents        bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS after_hours_fee_cents      bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS weekend_fee_cents          bigint NOT NULL DEFAULT 0,
    -- Charged when a visit is called off late or the contractor arrives and cannot proceed.
    ADD COLUMN IF NOT EXISTS cancellation_fee_cents     bigint NOT NULL DEFAULT 0,
    -- The floor for labour on any job, before parts.
    ADD COLUMN IF NOT EXISTS minimum_labor_cents        bigint NOT NULL DEFAULT 0;

COMMENT ON COLUMN contractors.visit_fee_cents IS
    'Standard diagnostic visit fee shown to the homeowner before dispatch and authorised on their card. Distinct from the FixBridge coordination fee, which may be waived during beta.';
COMMENT ON COLUMN contractors.emergency_fee_cents IS
    'Total visit fee for an emergency call-out, not an increment on visit_fee_cents.';
COMMENT ON COLUMN contractors.after_hours_fee_cents IS
    'Total visit fee outside normal working hours.';
COMMENT ON COLUMN contractors.weekend_fee_cents IS
    'Total visit fee at a weekend.';
COMMENT ON COLUMN contractors.cancellation_fee_cents IS
    'Charged on a late cancellation or a wasted trip.';
COMMENT ON COLUMN contractors.minimum_labor_cents IS
    'Minimum labour charged on any job, before parts.';
