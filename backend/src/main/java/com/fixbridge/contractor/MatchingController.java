package com.fixbridge.contractor;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.contractor.dto.MatchDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Contractor matching and the ratings that feed it.
 *
 * <p>These are the two ends of the same loop: matching ranks contractors partly on their ratings,
 * and ratings can only come from jobs that matching produced. Previously the ranking existed with
 * nothing calling it and no way to submit a rating, so two of its four inputs were constants.
 *
 * <p>Location is supplied per request rather than stored. A customer's coordinates are only needed
 * to answer "who is near me right now", and keeping them out of the database keeps the blast radius
 * of that data small.
 */
@RestController
@RequestMapping("/api/matching")
public class MatchingController {

    /** Enough choice to compare, few enough to read on a phone. */
    private static final int DEFAULT_LIMIT = 5;

    private final ContractorMatchingService matching;
    private final ContractorReviewService reviews;

    public MatchingController(ContractorMatchingService matching, ContractorReviewService reviews) {
        this.matching = matching;
        this.reviews = reviews;
    }

    /**
     * Rank contractors for a trade. Coordinates are optional — without them the results are still
     * ranked, just without the distance component, which is exactly what happened everywhere before
     * this endpoint existed.
     */
    @GetMapping("/contractors")
    public MatchDtos.MatchResult contractors(@RequestParam String trade,
                                             @RequestParam(required = false) Double lat,
                                             @RequestParam(required = false) Double lng,
                                             @RequestParam(required = false)
                                             @Min(1) @Max(20) Integer limit) {
        SecurityUtil.currentUser();   // authenticated callers only; the annotation is on the config
        return matching.match(trade, lat, lng, limit == null ? DEFAULT_LIMIT : limit);
    }

    /** Rate the contractor who did a completed job. */
    @PostMapping("/reviews")
    public MatchDtos.ReviewView review(@Valid @RequestBody MatchDtos.SubmitReviewRequest req) {
        return reviews.submit(SecurityUtil.currentUser().id(), req.jobId(), req.rating(),
                req.comment());
    }

    /** Whether the current customer can still review this job — so the UI can prompt only once. */
    @GetMapping("/reviews/eligibility/{jobId}")
    public MatchDtos.ReviewEligibility eligibility(@PathVariable UUID jobId) {
        return new MatchDtos.ReviewEligibility(jobId,
                reviews.canReview(SecurityUtil.currentUser().id(), jobId));
    }
}
