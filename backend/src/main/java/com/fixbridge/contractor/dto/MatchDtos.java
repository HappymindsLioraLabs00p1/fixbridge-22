package com.fixbridge.contractor.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MatchDtos {

    private MatchDtos() {}

    /**
     * One ranked contractor. `score` is exposed so an ordering can be explained rather than being
     * opaque — a contractor asking why they placed where they did deserves an answer.
     *
     * <p>`rating` and `distanceMiles` are nullable on purpose: a contractor with no reviews, or no
     * recorded location, is reported as unknown rather than being assigned a fabricated value.
     */
    public record ContractorMatch(
            UUID contractorId,
            String businessName,
            String trade,
            long completedJobs,
            Double rating,
            long reviewCount,
            Double distanceMiles,
            Long minTripChargeCents,
            Integer travelRadiusMiles,
            double score,
            String availability
    ) {}

    /**
     * `reason` is populated only when nothing matched. `tradeFilterApplied` is false when no
     * contractor has declared the trade — the caller should know the results are broader than asked
     * for rather than assuming a precise match.
     */
    public record MatchResult(
            String requiredTrade,
            List<ContractorMatch> matches,
            String reason,
            boolean tradeFilterApplied
    ) {}

    /** A rating being submitted. The contractor is derived from the job rather than supplied, so a
     *  caller cannot rate someone who never worked for them. */
    public record SubmitReviewRequest(
            @NotNull UUID jobId,
            @Min(1) @Max(5) int rating,
            @Size(max = 2000) String comment
    ) {}

    public record ReviewView(
            UUID id,
            UUID contractorId,
            UUID jobId,
            int rating,
            String comment,
            Instant createdAt
    ) {}

    public record ReviewEligibility(UUID jobId, boolean canReview) {}
}
