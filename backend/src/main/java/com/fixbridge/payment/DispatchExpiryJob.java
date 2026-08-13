package com.fixbridge.payment;

import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * Releases visit-fee holds on jobs nobody accepted.
 *
 * <p>Without this a homeowner is left with money reserved against a job that never happened. It is
 * invisible to us and very visible to them: the amount sits on their statement as pending, their
 * available balance is lower, and nothing in the app explains why. A hold that is never released is
 * worse than one never taken.
 *
 * <p>Bounded by how long a dispatch is given to find someone, not by how long the hold could legally
 * survive. Card authorisations typically expire on their own after about a week, but waiting for
 * that means a customer is out of pocket for days after we have stopped looking.
 */
@Component
public class DispatchExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(DispatchExpiryJob.class);

    /** Still actively looking for a contractor — a hold here is doing its job. */
    private static final Set<JobStatus> STILL_SEEKING = EnumSet.of(
            JobStatus.paid_for_dispatch,
            JobStatus.awaiting_contractor,
            JobStatus.contractor_invited);

    @Value("${fixbridge.dispatch.expiry-hours:48}")
    private long expiryHours;

    @Value("${fixbridge.dispatch.expiry-enabled:true}")
    private boolean enabled;

    private final JobRepository jobs;
    private final VisitFeeHoldService holds;

    public DispatchExpiryJob(JobRepository jobs, VisitFeeHoldService holds) {
        this.jobs = jobs;
        this.holds = holds;
    }

    /** Hourly. The window is measured in days, so a finer sweep would be pure noise. */
    @Scheduled(fixedDelayString = "${fixbridge.dispatch.expiry-interval-ms:3600000}",
               initialDelay = 120_000)
    @Transactional
    public void releaseExpiredHolds() {
        if (!enabled) return;

        Instant cutoff = Instant.now().minus(Duration.ofHours(expiryHours));
        int released = 0;

        for (Job job : jobs.findAll()) {
            if (job.getVisitFeeIntentId() == null) continue;      // nothing held
            if (job.getVisitFeeCapturedAt() != null) continue;    // already taken
            if (!STILL_SEEKING.contains(job.getStatus())) continue;
            if (job.getUpdatedAt() == null || job.getUpdatedAt().isAfter(cutoff)) continue;

            // Release only. The job is deliberately left where it is rather than cancelled: a
            // homeowner may still want the work, and returning their money is a separate decision
            // from abandoning their request.
            holds.release(job.getId(), "No contractor accepted within " + expiryHours + " hours");
            released++;
        }

        if (released > 0) {
            log.info("Released {} visit-fee hold(s) on jobs with no contractor after {}h",
                    released, expiryHours);
        }
    }
}
