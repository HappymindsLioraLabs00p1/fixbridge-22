-- Contractor matching needs data the schema didn't carry: which trades someone works, where they
-- are, and how previous jobs went. Without these, ranking could only use payout counts.
--
-- Ratings are derived from reviews rather than stored as a free-floating number, so a rating can
-- always be traced to the jobs that produced it.

ALTER TABLE contractors
  ADD COLUMN latitude   numeric(9,6),
  ADD COLUMN longitude  numeric(9,6),
  ADD COLUMN service_city text,
  ADD COLUMN service_postal_code text;

-- A contractor works several trades; a single column would force the same lie as a comma-separated
-- list and make "who can do electrical work" unindexable.
CREATE TABLE contractor_skills (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  trade         text NOT NULL,
  -- Years of experience in this specific trade, not overall.
  years         int,
  is_primary    boolean NOT NULL DEFAULT false,
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (contractor_id, trade)
);

CREATE TABLE contractor_reviews (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  contractor_id uuid NOT NULL REFERENCES contractors(id) ON DELETE CASCADE,
  job_id        uuid REFERENCES jobs(id) ON DELETE SET NULL,
  customer_id   uuid REFERENCES profiles(id) ON DELETE SET NULL,
  rating        int NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment       text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  -- One review per customer per job, so a rating cannot be inflated by repeat submissions.
  UNIQUE (job_id, customer_id)
);

-- Matching reads these on every search.
CREATE INDEX idx_contractor_skills_trade ON contractor_skills(trade);
CREATE INDEX idx_contractor_reviews_contractor ON contractor_reviews(contractor_id);
CREATE INDEX idx_contractors_location ON contractors(latitude, longitude)
  WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
