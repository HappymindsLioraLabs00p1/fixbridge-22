-- A job is paid out once.
--
-- Nothing stopped a second transfer being written for the same job. Two admins clicking at the same
-- moment both passed the status check before either wrote, and each sent money — a duplicate here is
-- not a stray row, it is a second real payment to a contractor that the platform cannot recall.
--
-- Partial by design. A reversed or failed transfer is history and must not block a legitimate retry;
-- only an outstanding or completed one reserves the job.
create unique index transfers_one_live_per_job
    on transfers (job_id)
    where status in ('pending', 'paid');
