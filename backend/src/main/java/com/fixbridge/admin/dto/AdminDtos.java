package com.fixbridge.admin.dto;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Admin-only payloads. Admin is the ONLY role that sees both contractor net and customer retail. */
public final class AdminDtos {

    private AdminDtos() {}

    public record InviteRequest(@NotNull UUID contractorId) {}

    public record CreateProposalRequest(
            @NotNull UUID bidId,
            String scope,
            long depositCents,
            String timeline,
            String warranty,
            String exclusions,
            String terms
    ) {}

    /** Dispatch-queue row — admin sees both the internal net estimate and the customer retail range. */
    public record AdminJobView(
            UUID jobId,
            JobStatus status,
            String title,
            String category,
            AiUrgency urgency,
            Long estContractorNetLow,
            Long estContractorNetHigh,
            Long customerRetailLow,
            Long customerRetailHigh
    ) {}

    /** Proposal view showing the confidential margin — admin only. */
    public record AdminProposalView(
            UUID proposalId,
            UUID jobId,
            long contractorNetCents,
            long retailTotalCents,
            long marginCents,
            ProposalStatus status
    ) {}
}
