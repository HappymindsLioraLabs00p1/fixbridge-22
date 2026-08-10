package com.fixbridge.contractor;

import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.dto.MatchDtos;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Customer ratings of completed work.
 *
 * <p>A review has to be earned: it is tied to a job the reviewer actually owns, which actually
 * reached a contractor, and which is actually finished. Free-floating ratings would make the
 * matching score trivial to poison, and matching is what decides who gets sent to someone's house.
 */
@Service
public class ContractorReviewService {

    private static final Logger log = LoggerFactory.getLogger(ContractorReviewService.class);

    /** Work has to be done before it can be judged. Anything earlier is a review of nothing. */
    private static final Set<JobStatus> REVIEWABLE = EnumSet.of(
            JobStatus.work_completed, JobStatus.customer_review_pending,
            JobStatus.admin_review_pending, JobStatus.payout_pending,
            JobStatus.paid_out, JobStatus.closed);

    private final ContractorReviewRepository reviews;
    private final JobRepository jobs;

    public ContractorReviewService(ContractorReviewRepository reviews, JobRepository jobs) {
        this.reviews = reviews;
        this.jobs = jobs;
    }

    @Transactional
    public MatchDtos.ReviewView submit(UUID customerId, UUID jobId, int rating, String comment) {
        if (rating < 1 || rating > 5) {
            throw ApiException.badRequest("A rating must be between 1 and 5.");
        }

        Job job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));

        // Ownership before existence of anything else — a customer may only rate their own job, and
        // the 403 must not leak whether the job is reviewable.
        if (!customerId.equals(job.getCustomerId())) {
            throw ApiException.forbidden();
        }
        if (job.getAssignedContractorId() == null) {
            throw ApiException.badRequest("This job has no contractor to review yet.");
        }
        if (!REVIEWABLE.contains(job.getStatus())) {
            throw ApiException.badRequest("This job isn't finished yet, so it can't be reviewed.");
        }
        // One review per job. Without this a single customer could rate the same job repeatedly and
        // move a contractor's average on their own.
        if (reviews.existsByJobIdAndCustomerId(jobId, customerId)) {
            throw ApiException.conflict("You've already reviewed this job.");
        }

        ContractorReview review = new ContractorReview();
        review.setContractorId(job.getAssignedContractorId());
        review.setJobId(jobId);
        review.setCustomerId(customerId);
        review.setRating(rating);
        review.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        reviews.save(review);

        log.info("Review {} recorded for contractor {} on job {}",
                rating, job.getAssignedContractorId(), jobId);

        return new MatchDtos.ReviewView(review.getId(), review.getContractorId(), jobId,
                review.getRating(), review.getComment(), review.getCreatedAt());
    }

    /** Whether this customer still owes a review for the job — drives the prompt in the UI. */
    @Transactional(readOnly = true)
    public boolean canReview(UUID customerId, UUID jobId) {
        return jobs.findById(jobId)
                .filter(j -> customerId.equals(j.getCustomerId()))
                .filter(j -> j.getAssignedContractorId() != null)
                .filter(j -> REVIEWABLE.contains(j.getStatus()))
                .isPresent()
                && !reviews.existsByJobIdAndCustomerId(jobId, customerId);
    }
}
