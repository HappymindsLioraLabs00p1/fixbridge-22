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

    public record CompletionRequest(@NotBlank String summary, String materialsUsed) {}
}
