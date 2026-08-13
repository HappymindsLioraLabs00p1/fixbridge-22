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

    /** Take the reserved money. Called when a contractor accepts the job. */
    @Transactional
    public void capture(UUID jobId) {
        Job job = requireHold(jobId);
        stripe.captureAuthorization(job.getVisitFeeIntentId(), job.getVisitFeeAuthorizedCents());
        job.setVisitFeeCapturedAt(java.time.Instant.now());
        jobs.save(job);
        log.info("Visit fee of {} captured for job {}", job.getVisitFeeAuthorizedCents(), jobId);
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

    private Job requireHold(UUID jobId) {
        Job job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        if (job.getVisitFeeIntentId() == null) {
            throw ApiException.conflict("No visit fee is authorised for this job.");
        }
        if (job.getVisitFeeCapturedAt() != null) {
            throw ApiException.conflict("The visit fee has already been captured.");
        }
        return job;
    }
}
