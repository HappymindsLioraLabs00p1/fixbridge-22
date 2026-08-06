package com.fixbridge.job.dto;

import com.fixbridge.common.enums.ProposalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public final class ChangeOrderDtos {

    private ChangeOrderDtos() {}

    /** Contractor documents newly discovered work with its confidential net cost. */
    public record SubmitRequest(
            @NotBlank String description,
            @Positive long addedNetCents,
            Integer addedDays
    ) {}

    /** Customer-facing: added retail only — never the net or the margin. */
    public record CustomerView(
            UUID id,
            UUID jobId,
            String description,
            long addedRetailCents,
            Integer addedDays,
            ProposalStatus status
    ) {}

    /** Admin-only: shows added net, retail and the confidential margin. */
    public record AdminView(
            UUID id,
            UUID jobId,
            String description,
            long addedNetCents,
            long addedRetailCents,
            long marginCents,
            Integer addedDays,
            ProposalStatus status
    ) {}
}
