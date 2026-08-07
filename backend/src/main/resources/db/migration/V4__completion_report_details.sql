-- FR-JOB-7 requires the contractor's completion proof to include an invoice and warranty, and
-- FR-JOB-8 requires an explicit customer/admin confirmation. The base table already carries
-- arrival/completion times, before/after photo keys and materials; add the remaining fields.

ALTER TABLE completion_reports ADD COLUMN invoice_url text;
ALTER TABLE completion_reports ADD COLUMN warranty_text text;
ALTER TABLE completion_reports ADD COLUMN approved_at timestamptz;

CREATE INDEX idx_completion_reports_job ON completion_reports(job_id);
