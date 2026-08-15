package com.fixbridge.job;

import com.fixbridge.ai.AiAssessmentEntity;
import com.fixbridge.ai.AiAssessmentRepository;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.contractor.ContractorMatchingService;
import com.fixbridge.contractor.dto.MatchDtos;
import com.fixbridge.notification.NotificationService;
import com.fixbridge.pricing.JobPricing;
import com.fixbridge.pricing.JobPricingRepository;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Invites contractors to a job that has reached dispatch.
 *
 * <p>Reaching {@code awaiting_contractor} used to set nothing in motion. Invitations were created in
 * one place only — an admin choosing a contractor by hand — so a paid job waited on a person who had
 * no prompt to act, and the customer saw "awaiting contractor" indefinitely. That reads as nobody
 * being available rather than nobody having been asked.
 *
 * <p>This is not a second dispatch system. Eligibility and ranking stay in
 * {@link ContractorMatchingService}, which already filters to approved, compliant, payout-enabled
 * contractors — so anything it returns is dispatchable and none of those rules are restated here. The
 * invitation, the transition and the notification are the same three steps the admin path takes.
 */
@Service
public class AutoDispatchService {

    private static final Logger log = LoggerFactory.getLogger(AutoDispatchService.class);

    /** Invite several at once: one contractor who never responds would otherwise stall the job. */
    @Value("${fixbridge.dispatch.fan-out:3}")
    private int fanOut;

    @Value("${fixbridge.dispatch.auto-enabled:true}")
    private boolean enabled;

    private final JobService jobService;
    private final ContractorMatchingService matching;
    private final JobInvitationRepository invitations;
    private final AiAssessmentRepository assessments;
    private final PropertyRepository properties;
    private final JobPricingRepository jobPricing;
    private final NotificationService notifications;

    public AutoDispatchService(JobService jobService, ContractorMatchingService matching,
                               JobInvitationRepository invitations, AiAssessmentRepository assessments,
                               PropertyRepository properties, JobPricingRepository jobPricing,
                               NotificationService notifications) {
        this.jobService = jobService;
        this.matching = matching;
        this.invitations = invitations;
        this.assessments = assessments;
        this.properties = properties;
        this.jobPricing = jobPricing;
        this.notifications = notifications;
    }

    /**
     * Invite the best-ranked contractors to a job awaiting one. Returns how many were invited.
     *
     * <p>Runs in its own transaction. It is called immediately after a payment succeeds, and a
     * dispatch that fails must not take the payment down with it: the money moved, and the job can be
     * dispatched again afterwards.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int dispatch(UUID jobId) {
        if (!enabled) return 0;

        Job job = jobService.requireJob(jobId);
        // The status is the idempotency guard: the first run moves the job to contractor_invited, so
        // a repeat cannot invite a second round. Re-dispatching a stalled job is an admin decision.
        if (job.getStatus() != JobStatus.awaiting_contractor) {
            log.debug("Job {} is {} — not awaiting dispatch", jobId, job.getStatus());
            return 0;
        }

        String trade = assessments.findFirstByJobIdOrderByCreatedAtDesc(jobId)
                .map(AiAssessmentEntity::getRecommendedTrade).orElse(null);
        Property property = job.getPropertyId() == null ? null
                : properties.findById(job.getPropertyId()).orElse(null);

        MatchDtos.MatchResult result = matching.match(trade,
                coordinate(property == null ? null : property.getLatitude()),
                coordinate(property == null ? null : property.getLongitude()),
                fanOut);

        if (result.matches().isEmpty()) {
            // Deliberately left at awaiting_contractor. There is no contractor to assign, and moving
            // the job on would claim one exists; an admin can invite by hand or the sweep releases
            // the visit-fee hold. Logged loudly because from outside this is indistinguishable from
            // dispatch never having run.
            log.warn("No contractor available for job {} (trade '{}' → '{}'): {}",
                    jobId, trade, result.requiredTrade(), result.reason());
            return 0;
        }

        Long expectedNet = jobPricing.findByJobId(jobId)
                .map(JobPricing::getEstContractorNetLow).orElse(null);

        int invited = 0;
        for (MatchDtos.ContractorMatch match : result.matches()) {
            // Belt and braces alongside the status guard: an invitation already on file is never
            // duplicated, whatever route got us here.
            if (invitations.findByJobIdAndContractorId(jobId, match.contractorId()).isPresent()) {
                continue;
            }
            invitations.save(new JobInvitation(jobId, match.contractorId(), expectedNet));
            invited++;

            // A notification failure must not undo an invitation that exists — the contractor can
            // still find the job in their list, and losing the invitation would leave nothing to
            // find. Same treatment the visit-fee capture gets after a contractor accepts.
            try {
                notifications.contractorInvited(match.contractorId(), jobId);
            } catch (Exception e) {
                log.warn("Invitation for job {} saved but notifying contractor {} failed: {}",
                        jobId, match.contractorId(), e.getMessage());
            }
        }

        if (invited > 0) {
            jobService.transition(job, JobStatus.contractor_invited, null);
            log.info("Auto-dispatched job {} to {} contractor(s) for trade '{}'",
                    jobId, invited, result.requiredTrade());
        }
        return invited;
    }

    private static Double coordinate(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
