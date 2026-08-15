package com.fixbridge.payment;

import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The hold placed on a homeowner's card for the contractor's visit fee.
 *
 * <p>Reserved when they accept the quote, taken when a contractor accepts the job, released if
 * nobody does. A hold rather than a charge because at the point of accepting, nobody has agreed to
 * come out — and refunding a charge is not equivalent to never making one. A refund takes days,
 * appears on a statement as a reversal, and reads to the customer as something having gone wrong.
 *
 * <p>Two rules the rest of the system depends on:
 *
 * <ul>
 *   <li>Never capture more than was authorised. The homeowner agreed to a specific figure.</li>
 *   <li>A hold is captured or released, never both, and never twice.</li>
 * </ul>
 */
@Service
public class VisitFeeHoldService {

    private static final Logger log = LoggerFactory.getLogger(VisitFeeHoldService.class);
    private static final String CURRENCY = "usd";

    private final StripeClient stripe;
    private final JobRepository jobs;

    public VisitFeeHoldService(StripeClient stripe, JobRepository jobs) {
        this.stripe = stripe;
        this.jobs = jobs;
    }

    /**
     * Reserve the visit fee. Called when the homeowner accepts the quote, before dispatch.
     *
     * @param amountCents the figure the homeowner was shown and agreed to — not recalculated here,
     *                    because a fee that changes between the screen and the hold is a fee the
     *                    customer never consented to.
     */
    @Transactional
    public StripeClient.Authorization hold(UUID jobId, long amountCents) {
        if (amountCents <= 0) {
            throw ApiException.badRequest("A visit fee must be a positive amount to authorise.");
        }
        Job job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));

        if (job.getVisitFeeIntentId() != null) {
            // Authorising twice would place two holds on the same card for one visit.
            throw ApiException.conflict("A visit fee is already authorised for this job.");
        }

        var auth = stripe.authorize(amountCents, CURRENCY, jobId.toString());
        job.setVisitFeeIntentId(auth.paymentIntentId());
        job.setVisitFeeAuthorizedCents(amountCents);
        jobs.save(job);

        log.info("Visit fee of {} authorised for job {}", amountCents, jobId);
        return auth;
    }

    /**
     * Take the reserved money. Called when a contractor accepts the job.
     *
     * <p>Forgiving in the same way {@link #release} is, and for a stronger reason: this runs inside a
     * contractor's acceptance, and the acceptance must survive whatever the money does. A job may
     * legitimately have no hold — one is placed only when the homeowner is quoted a visit fee, and a
     * waived dispatch fee reaches dispatch without ever authorising one.
     *
     * <p>Nothing here throws, which is the point rather than an oversight. An exception crossing this
     * transaction boundary marks the <em>caller's</em> transaction rollback-only, so the acceptance is
     * thrown away at commit time even though the caller catches the exception and carries on. That is
     * what turned a missing hold into a 500 and silently lost the contractor's bid. Money left
     * uncaptured is visible and recoverable by an admin; a lost acceptance is neither.
     *
     * @return true when money was actually taken
     */
    @Transactional
    public boolean capture(UUID jobId) {
        Job job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Cannot capture a visit fee for unknown job {}", jobId);
            return false;
        }
        if (job.getVisitFeeIntentId() == null) {
            log.info("No visit fee is authorised for job {} — nothing to capture", jobId);
            return false;
        }
        if (job.getVisitFeeCapturedAt() != null) {
            return false;   // idempotent: never take the same visit fee twice
        }
        try {
            stripe.captureAuthorization(job.getVisitFeeIntentId(), job.getVisitFeeAuthorizedCents());
        } catch (Exception e) {
            // The hold stands and an admin can capture or release it deliberately.
            log.warn("Capturing the visit fee for job {} failed — the hold is left in place: {}",
                    jobId, e.getMessage());
            return false;
        }
        job.setVisitFeeCapturedAt(java.time.Instant.now());
        jobs.save(job);
        log.info("Visit fee of {} captured for job {}", job.getVisitFeeAuthorizedCents(), jobId);
        return true;
    }

    /**
     * Return the reserved money. Called when no contractor accepts, or the homeowner cancels
     * before one does.
     *
     * <p>Deliberately forgiving: releasing a hold that is already gone is not an error worth
     * failing a request over, and the alternative — a caller that gives up halfway through
     * cleaning up — leaves money reserved on a customer's card for a job nobody is doing.
     */
    @Transactional
    public void release(UUID jobId, String reason) {
        Job job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        if (job.getVisitFeeIntentId() == null || job.getVisitFeeCapturedAt() != null) {
            return;
        }
        try {
            stripe.releaseAuthorization(job.getVisitFeeIntentId(), reason);
        } catch (Exception e) {
            log.warn("Releasing the visit fee hold for job {} failed: {}", jobId, e.getMessage());
        }
        job.setVisitFeeIntentId(null);
        job.setVisitFeeAuthorizedCents(null);
        jobs.save(job);
        log.info("Visit fee hold released for job {}: {}", jobId, reason);
    }

}
