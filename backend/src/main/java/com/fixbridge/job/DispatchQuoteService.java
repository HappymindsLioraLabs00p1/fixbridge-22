package com.fixbridge.job;

import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.contractor.*;
import com.fixbridge.job.dto.DispatchQuoteDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a homeowner will be charged, shown before anyone is dispatched.
 *
 * <p>Three amounts, kept apart deliberately:
 *
 * <ol>
 *   <li><b>FixBridge fee</b> — coordination. Waived during beta.</li>
 *   <li><b>Contractor visit fee</b> — the contractor's own diagnostic charge. <em>Never</em> waived
 *       by a FixBridge promotion; it is their money, not ours to discount.</li>
 *   <li><b>Repair estimate</b> — unknown until someone has looked, and agreed separately.</li>
 * </ol>
 *
 * <p>They are separated because the failure mode is specific and expensive: a homeowner reads
 * "$0" next to FixBridge, assumes the visit is free, and disputes the charge when the contractor
 * bills them. The quote therefore states the visit fee as its own line with its own total, and
 * never presents a combined figure that could be mistaken for the whole cost.
 */
@Service
public class DispatchQuoteService {

    /** Coordination is free during beta. A field rather than a literal so it can be turned on. */
    private static final long FIXBRIDGE_BETA_FEE_CENTS = 0L;

    private final ContractorRepository contractors;
    private final ContractorSkillRepository skills;
    private final ComplianceService compliance;
    private final VisitFeeCalculator visitFees;
    private final JobService jobs;

    public DispatchQuoteService(ContractorRepository contractors, ContractorSkillRepository skills,
                                ComplianceService compliance, VisitFeeCalculator visitFees,
                                JobService jobs) {
        this.contractors = contractors;
        this.skills = skills;
        this.compliance = compliance;
        this.visitFees = visitFees;
        this.jobs = jobs;
    }

    /**
     * The quote for a job, from the rates of the contractors who could actually take it.
     *
     * <p>A range rather than a single number, because the contractor is not chosen yet. Quoting the
     * cheapest alone would understate what many homeowners end up paying.
     */
    @Transactional(readOnly = true)
    public DispatchQuoteDtos.DispatchQuote quoteFor(UUID jobId, String trade, boolean emergency) {
        Job job = jobs.requireJob(jobId);

        Set<UUID> tradeMatches = trade == null ? Set.of()
                : skills.findByTradeIgnoreCase(trade).stream()
                        .map(ContractorSkill::getContractorId).collect(Collectors.toSet());

        List<Contractor> eligible = contractors.findAll().stream()
                .filter(c -> c.getStatus() == ContractorStatus.approved)
                .filter(Contractor::isPayoutsEnabled)
                .filter(c -> compliance.isCompliant(c.getId()))
                .filter(c -> tradeMatches.isEmpty() || tradeMatches.contains(c.getId()))
                .toList();

        List<VisitFeeCalculator.VisitFee> fees = eligible.stream()
                .map(c -> visitFees.forJob(c, emergency))
                // A contractor who has published no rate cannot contribute a number to a range the
                // homeowner is about to accept.
                .filter(f -> f.amountCents() > 0)
                .toList();

        if (fees.isEmpty()) {
            return new DispatchQuoteDtos.DispatchQuote(
                    job.getId(), FIXBRIDGE_BETA_FEE_CENTS, null, null, null, false,
                    eligible.size(),
                    "No contractor has published a visit fee for this trade yet. "
                  + "We'll confirm the visit fee with you before anyone is dispatched.");
        }

        long low = fees.stream().mapToLong(VisitFeeCalculator.VisitFee::amountCents).min().orElse(0);
        long high = fees.stream().mapToLong(VisitFeeCalculator.VisitFee::amountCents).max().orElse(0);
        String basis = fees.get(0).basis();
        String explanation = fees.get(0).explanation();

        return new DispatchQuoteDtos.DispatchQuote(
                job.getId(), FIXBRIDGE_BETA_FEE_CENTS, low, high, basis, true,
                eligible.size(), explanation);
    }
}
