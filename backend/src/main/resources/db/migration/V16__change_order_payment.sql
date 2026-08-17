-- Bill approved extra work.
--
-- A change order the customer approved was never charged. The code said "the added retail is billed
-- at final invoice" and no final invoice exists, so the platform paid the contractor for the extra
-- work — since the payout now includes it — and collected nothing.
--
-- The payment needs to name the change order it settles. Without that link two change orders on one
-- job are indistinguishable, so nothing could tell an unpaid second one from the paid first, and
-- nothing could stop the same one being charged twice.
alter table payments
    add column change_order_id uuid references change_orders(id);

-- One live charge per change order. A customer must never be billed twice for the same extra work,
-- and a retried checkout must find the existing payment rather than opening a second.
--
-- Partial: a failed or cancelled attempt is history and must not block a fresh one, exactly as with
-- proposals and payouts.
create unique index payments_one_live_per_change_order
    on payments (change_order_id)
    where change_order_id is not null
      and status in ('requires_payment', 'processing', 'succeeded');
