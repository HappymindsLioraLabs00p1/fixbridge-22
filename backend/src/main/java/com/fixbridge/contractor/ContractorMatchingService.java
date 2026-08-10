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

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Ranks contractors for a job.
 *
 * <p>Matching lives in Java because the contractor records do — the Python service holds no
 * business state and must not query these tables.
 *
 * <p>Compliance is a filter rather than a ranking factor. An uninsured contractor is not a worse
 * match; they are not a match at all, and scoring them low would eventually surface one on a thin
 * result set.
 *
 * <p>The available signals are limited by the schema: there is no rating, no trade list and no
 * location on a contractor today, so ranking uses completed work, price and coverage. Distance and
 * rating need columns that don't yet exist.
 */
@Service
public class ContractorMatchingService {

    private static final Logger log = LoggerFactory.getLogger(ContractorMatchingService.class);

    private final ContractorRepository contractors;
    private final TransferRepository transfers;
    private final ComplianceService compliance;

    public ContractorMatchingService(ContractorRepository contractors, TransferRepository transfers,
                                     ComplianceService compliance) {
        this.contractors = contractors;
        this.transfers = transfers;
        this.compliance = compliance;
    }

    @Transactional(readOnly = true)
    public MatchDtos.MatchResult match(String requiredTrade, int limit) {
        // Completed payouts per contractor — the only real track-record signal available.
        Map<UUID, Long> completed = transfers.findAll().stream()
                .filter(t -> t.getStatus() == TransferStatus.paid)
                .collect(Collectors.groupingBy(Transfer::getContractorId, Collectors.counting()));

        List<Contractor> all = contractors.findAll();

        List<MatchDtos.ContractorMatch> matches = all.stream()
                // Hard filter, not a penalty: dispatching an uninsured contractor is the thing this
                // whole subsystem exists to prevent.
                .filter(c -> c.getStatus() == ContractorStatus.approved)
                .filter(c -> compliance.isCompliant(c.getId()))
                .filter(Contractor::isPayoutsEnabled)
                .map(c -> {
                    long jobs = completed.getOrDefault(c.getId(), 0L);
                    return new MatchDtos.ContractorMatch(
                            c.getId(), c.getBusinessName(), requiredTrade,
                            jobs, c.getMinTripChargeCents(), c.getTravelRadiusMiles(),
                            score(jobs, c), "AVAILABLE");
                })
                .sorted(Comparator.comparingDouble(MatchDtos.ContractorMatch::score).reversed())
                .limit(limit)
                .toList();

        long excluded = all.size() - matches.size();
        log.info("Contractor match for trade {}: {} eligible of {} ({} excluded on compliance or payouts)",
                requiredTrade, matches.size(), all.size(), excluded);

        return new MatchDtos.MatchResult(requiredTrade, matches,
                matches.isEmpty()
                        ? "No compliant contractor is available for this trade right now."
                        : null);
    }

    /**
     * A deliberately simple, explainable score. Track record dominates, price breaks ties, and
     * coverage contributes a little — an opaque formula here would be impossible to justify to a
     * contractor who asks why they rank where they do.
     */
    private double score(long completedJobs, Contractor c) {
        double trackRecord = Math.min(completedJobs, 20) * 3.0;          // up to 60
        long trip = c.getMinTripChargeCents() == null ? 0 : c.getMinTripChargeCents();
        double price = Math.max(0, 25 - (trip / 2000.0));                 // up to 25, cheaper is better
        int radius = c.getTravelRadiusMiles() == null ? 0 : c.getTravelRadiusMiles();
        double coverage = Math.min(radius, 50) / 50.0 * 15.0;             // up to 15
        return Math.round((trackRecord + price + coverage) * 10) / 10.0;
    }
}
