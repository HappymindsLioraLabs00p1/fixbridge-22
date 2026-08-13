package com.fixbridge.job.dto;

import java.util.UUID;

public final class DispatchQuoteDtos {

    private DispatchQuoteDtos() {}

    /**
     * What a homeowner will pay, before anyone is dispatched.
     *
     * <p>The three amounts are separate fields on purpose. A single total would let "$0 FixBridge
     * fee" read as "$0 to have someone come out", and that misunderstanding surfaces as a disputed
     * charge after the contractor has already driven to the property.
     *
     * <p>{@code visitFeeLowCents} and {@code visitFeeHighCents} are null when no eligible
     * contractor has published a rate. The client must then say the fee will be confirmed, not
     * show a zero — the two mean opposite things to somebody deciding whether to proceed.
     *
     * <p>There is deliberately no repair-estimate field. It cannot be known until a contractor has
     * looked at the problem, and inventing a placeholder is how a quote becomes a complaint.
     */
    public record DispatchQuote(
            UUID jobId,
            /** FixBridge coordination. Zero during beta. */
            long fixbridgeFeeCents,
            /** The contractor's own diagnostic charge — never waived by a FixBridge promotion. */
            Long visitFeeLowCents,
            Long visitFeeHighCents,
            /** STANDARD, WEEKEND, AFTER_HOURS or EMERGENCY — why this rate applies. */
            String visitFeeBasis,
            /** False when no rate is published and the fee must be confirmed manually. */
            boolean visitFeeKnown,
            int availableContractors,
            String explanation
    ) {}
}
