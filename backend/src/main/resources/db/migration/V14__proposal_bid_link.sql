-- One live proposal per job, and a record of which bid priced it.
--
-- A proposal was linked only to its job. Which bid produced the price was implied by the contractor
-- assigned to the job at the same moment, so a later reassignment left no way to answer "what were
-- we quoted, and by whom" — the one question an argument about an invoice turns on.
alter table proposals
    add column bid_id uuid references bids(id) on delete set null;

-- Nothing stopped a second proposal for the same job. Two live proposals mean two prices for one
-- piece of work, and whichever the customer approved would be the one that billed them.
--
-- Partial by design: draft, sent and approved are live, and declined or expired ones are history.
-- A customer who declines must be able to receive a fresh proposal, which is the whole point of
-- declining, so those states are deliberately left outside the constraint.
create unique index proposals_one_live_per_job
    on proposals (job_id)
    where status in ('draft', 'sent', 'approved');
