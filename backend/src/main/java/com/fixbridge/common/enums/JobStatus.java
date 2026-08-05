package com.fixbridge.common.enums;

/** Canonical job lifecycle. Matches PostgreSQL {@code job_status} (Appendix B of the spec). */
public enum JobStatus {
    draft,
    ai_review_complete,
    awaiting_service_payment,
    paid_for_dispatch,
    awaiting_contractor,
    contractor_invited,
    contractor_accepted,
    awaiting_bid,
    bid_received,
    proposal_sent,
    awaiting_customer_approval,
    approved,
    scheduled,
    contractor_en_route,
    work_started,
    change_order_pending,
    work_completed,
    customer_review_pending,
    admin_review_pending,
    payout_pending,
    paid_out,
    closed,
    canceled,
    refunded,
    disputed
}
