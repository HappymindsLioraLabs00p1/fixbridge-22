package com.fixbridge.contractor.dto;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.InvitationStatus;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.UUID;

public final class ContractorDtos {

    private ContractorDtos() {}

    public record OnboardRequest(@NotBlank String businessName, String contactPhone) {}

    public record ContractorView(UUID id, String businessName, ContractorStatus status,
                                 boolean payoutsEnabled, String onboardingUrl) {}

    /**
     * Pre-authorization invitation view: general area, trade, urgency and expected NET payout only —
     * never the full address, customer contact, or customer retail price (spec §11.3, §9.7).
     */
    public record InvitationView(
            UUID jobId,
            InvitationStatus status,
            /**
             * Where the job itself has got to, which is a different question from where the
             * invitation has. Without it the card cannot tell a job waiting to be started from one
             * already finished, so it offered every action on every invitation.
             */
            com.fixbridge.common.enums.JobStatus jobStatus,
            String generalArea,
            String recommendedTrade,
            AiUrgency urgency,
            Long expectedNetCents
    ) {}

    /** Confidential contractor net bid. The customer never sees these figures. */
    public record BidRequest(
            long laborCents,
            long materialsCents,
            long equipmentCents,
            long travelCents,
            long permitCents,
            long disposalCents,
            LocalDate earliestStart,
            Integer durationDays,
            String warranty,
            String exclusions
    ) {
        public long netTotalCents() {
            return laborCents + materialsCents + equipmentCents + travelCents + permitCents + disposalCents;
        }
    }

    /** Proof of completion (FR-JOB-7). Photos are storage object keys from the media upload flow. */
    public record CompletionRequest(
            @NotBlank String summary,
            String materialsUsed,
            java.time.Instant arrivedAt,
            java.time.Instant completedAt,
            java.util.List<String> beforeKeys,
            java.util.List<String> afterKeys,
            String invoiceUrl,
            String warrantyText
    ) {}
}
