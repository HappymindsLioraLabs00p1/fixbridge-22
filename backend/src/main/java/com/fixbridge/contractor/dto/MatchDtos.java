package com.fixbridge.contractor.dto;

import java.util.List;
import java.util.UUID;

public final class MatchDtos {

    private MatchDtos() {}

    /**
     * One ranked contractor. `score` is exposed so a ranking can be explained rather than being an
     * opaque ordering — a contractor asking why they placed where they did deserves an answer.
     */
    public record ContractorMatch(
            UUID contractorId,
            String businessName,
            String trade,
            long completedJobs,
            Long minTripChargeCents,
            Integer travelRadiusMiles,
            double score,
            String availability
    ) {}

    /** `reason` is populated only when nothing matched, so the caller can say why. */
    public record MatchResult(String requiredTrade, List<ContractorMatch> matches, String reason) {}
}
