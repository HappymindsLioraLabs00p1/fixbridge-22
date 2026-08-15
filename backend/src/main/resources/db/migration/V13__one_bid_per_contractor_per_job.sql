-- One bid per contractor per job.
--
-- Nothing stopped a contractor submitting the same invitation twice. The extra row was not merely
-- untidy: the payout selects a contractor's most recent bid, so re-submitting silently changed the
-- amount the platform paid out, and the admin's bid list offered the same contractor twice with
-- different numbers and no way to tell which was meant.
--
-- job_invitations already carries exactly this constraint. Bids were the half of the same
-- relationship that never got one.

-- Existing duplicates must go before the constraint can apply. The EARLIEST is kept: that is the
-- bid that was on file when the invitation moved to accepted and the job to bid_received, so it is
-- the one the rest of the record already refers to. Keeping the latest instead would retroactively
-- honour a re-submission that should never have been accepted.
--
-- id breaks a tie on identical timestamps; without it two rows written in the same instant would
-- both survive and the constraint would still fail.
delete from bids b
using bids other
where b.job_id = other.job_id
  and b.contractor_id = other.contractor_id
  and (other.created_at, other.id) < (b.created_at, b.id);

alter table bids
    add constraint bids_job_id_contractor_id_key unique (job_id, contractor_id);
