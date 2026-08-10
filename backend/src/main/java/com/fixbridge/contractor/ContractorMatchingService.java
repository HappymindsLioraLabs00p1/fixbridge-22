package com.fixbridge.contractor;

import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.TransferStatus;
import com.fixbridge.contractor.dto.MatchDtos;
import com.fixbridge.payment.Transfer;
import com.fixbridge.payment.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ranks contractors for a job.
 *
 * <p>Matching lives in Java because the contractor records do — the Python service holds no
 * business state and must not query these tables.
 *
 * <p>Compliance and trade are filters rather than ranking factors. An uninsured contractor is not a
 * worse match; they are not a match at all, and scoring them low would eventually surface one on a
 * thin result set. The same reasoning applies to trade: a plumber is not a low-scoring electrician.
 */
@Service
public class ContractorMatchingService {

    private static final Logger log = LoggerFactory.getLogger(ContractorMatchingService.class);

    /** A contractor with no reviews sits at the median rather than at zero — no history is not the
     *  same as bad history, and starting at zero would make a new contractor unbookable. */
    private static final double UNRATED_BASELINE = 3.5;
    private static final double EARTH_RADIUS_MILES = 3958.8;

    private final ContractorRepository contractors;
    private final ContractorSkillRepository skills;
    private final ContractorReviewRepository reviews;
    private final TransferRepository transfers;
    private final ComplianceService compliance;

    public ContractorMatchingService(ContractorRepository contractors,
                                     ContractorSkillRepository skills,
                                     ContractorReviewRepository reviews,
                                     TransferRepository transfers,
                                     ComplianceService compliance) {
        this.contractors = contractors;
        this.skills = skills;
        this.reviews = reviews;
        this.transfers = transfers;
        this.compliance = compliance;
    }

    @Transactional(readOnly = true)
    public MatchDtos.MatchResult match(String requiredTrade, Double customerLat, Double customerLng,
                                       int limit) {
        Map<UUID, Long> completed = transfers.findAll().stream()
                .filter(t -> t.getStatus() == TransferStatus.paid)
                .collect(Collectors.groupingBy(Transfer::getContractorId, Collectors.counting()));

        Map<UUID, double[]> ratings = new HashMap<>();   // [average, count]
        for (ContractorReview r : reviews.findAll()) {
            double[] agg = ratings.computeIfAbsent(r.getContractorId(), k -> new double[2]);
            agg[0] += r.getRating();
            agg[1] += 1;
        }

        // Trade is a filter. If nobody declares the trade, fall back to every compliant contractor
        // rather than returning nothing — a customer with an emergency needs somebody.
        Set<UUID> tradeMatches = skills.findByTradeIgnoreCase(requiredTrade).stream()
                .map(ContractorSkill::getContractorId).collect(Collectors.toSet());
        boolean tradeFilterUsable = !tradeMatches.isEmpty();

        List<Contractor> eligible = contractors.findAll().stream()
                .filter(c -> c.getStatus() == ContractorStatus.approved)
                .filter(c -> compliance.isCompliant(c.getId()))
                .filter(Contractor::isPayoutsEnabled)
                .filter(c -> !tradeFilterUsable || tradeMatches.contains(c.getId()))
                .toList();

        List<MatchDtos.ContractorMatch> matches = eligible.stream()
                .map(c -> {
                    long jobs = completed.getOrDefault(c.getId(), 0L);
                    double[] agg = ratings.get(c.getId());
                    Double rating = agg == null ? null : Math.round((agg[0] / agg[1]) * 10) / 10.0;
                    long reviewCount = agg == null ? 0 : (long) agg[1];
                    Double distance = distanceMiles(customerLat, customerLng,
                            c.getLatitude(), c.getLongitude());
                    return new MatchDtos.ContractorMatch(
                            c.getId(), c.getBusinessName(), requiredTrade, jobs,
                            rating, reviewCount, distance,
                            c.getMinTripChargeCents(), c.getTravelRadiusMiles(),
                            score(jobs, rating, distance, c), availability(distance, c));
                })
                // Out of range is reported rather than hidden, so a customer in a thin area can see
                // that somebody exists but is far away.
                .sorted(Comparator.comparingDouble(MatchDtos.ContractorMatch::score).reversed())
                .limit(limit)
                .toList();

        log.info("Match for {}: {} of {} contractors eligible (trade filter {})",
                requiredTrade, matches.size(), contractors.count(),
                tradeFilterUsable ? "applied" : "unavailable — no declared skills");

        return new MatchDtos.MatchResult(requiredTrade, matches,
                matches.isEmpty() ? "No compliant contractor is available for this trade right now."
                        : null,
                tradeFilterUsable);
    }

    /**
     * Explainable by design. Track record and rating dominate because they predict outcome;
     * distance and price break ties. An opaque formula could not be justified to a contractor who
     * asks why they rank where they do.
     */
    private double score(long completedJobs, Double rating, Double distanceMiles, Contractor c) {
        double track = Math.min(completedJobs, 20) * 2.0;                       // up to 40
        double quality = ((rating == null ? UNRATED_BASELINE : rating) / 5.0) * 30.0;  // up to 30
        double proximity = distanceMiles == null ? 10.0                          // unknown ≠ far
                : Math.max(0, 20 - distanceMiles);                               // up to 20
        long trip = c.getMinTripChargeCents() == null ? 0 : c.getMinTripChargeCents();
        double price = Math.max(0, 10 - (trip / 5000.0));                        // up to 10
        return Math.round((track + quality + proximity + price) * 10) / 10.0;
    }

    private String availability(Double distanceMiles, Contractor c) {
        Integer radius = c.getTravelRadiusMiles();
        if (distanceMiles != null && radius != null && distanceMiles > radius) {
            return "OUT_OF_RANGE";
        }
        return "AVAILABLE";
    }

    /** Great-circle distance. Null whenever either side lacks coordinates — an unknown distance is
     *  reported as unknown rather than guessed at. */
    private Double distanceMiles(Double lat1, Double lng1, BigDecimal lat2, BigDecimal lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return null;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1);
        double dLng = Math.toRadians(lng2.doubleValue() - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return Math.round(EARTH_RADIUS_MILES * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)) * 10) / 10.0;
    }
}
