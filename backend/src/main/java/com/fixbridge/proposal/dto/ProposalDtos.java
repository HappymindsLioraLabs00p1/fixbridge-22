package com.fixbridge.proposal.dto;

import com.fixbridge.common.enums.ProposalStatus;

import java.util.UUID;

public final class ProposalDtos {

    private ProposalDtos() {}

    /** Customer-safe proposal view: retail price only — never the contractor net or FixBridge margin. */
    public record CustomerProposalView(
            UUID proposalId,
            UUID jobId,
            String scope,
            long retailTotalCents,
            long depositCents,
            String timeline,
            String warranty,
            String exclusions,
            String terms,
            ProposalStatus status
    ) {}

    /** Why the customer turned it down. Optional — a decline is valid without an explanation. */
    public record DeclineRequest(String reason) {}
}
