-- Graceful degradation for the Python AI service (approved change).
--
-- Assessment used to be all-or-nothing: if the provider failed, reporting an issue failed with it.
-- With assessment moving to a separate service, an outage there must not stop a customer reporting
-- a problem — the job is recorded and the assessment is retried.

CREATE TYPE assessment_status AS ENUM ('completed', 'pending', 'failed');

ALTER TABLE ai_assessments
  ADD COLUMN status assessment_status NOT NULL DEFAULT 'completed',
  ADD COLUMN attempts int NOT NULL DEFAULT 1,
  ADD COLUMN last_error text,
  ADD COLUMN last_attempt_at timestamptz;

-- Everything already stored succeeded, so the default is correct for existing rows.

-- Retry sweeps look for unfinished work; index only those rows, since completed assessments are
-- the overwhelming majority and never match.
CREATE INDEX idx_ai_assessments_retryable
  ON ai_assessments(status, last_attempt_at)
  WHERE status IN ('pending', 'failed');
