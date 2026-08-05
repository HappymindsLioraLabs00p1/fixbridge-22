package com.fixbridge.job;

import com.fixbridge.ai.AiAssessmentEntity;
import com.fixbridge.ai.AiAssessmentRepository;
import com.fixbridge.ai.AiService;
import com.fixbridge.ai.AssessmentResult;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.dto.JobDtos;
import com.fixbridge.pricing.JobPricing;
import com.fixbridge.pricing.JobPricingRepository;
import com.fixbridge.pricing.PricingEngine;
import com.fixbridge.pricing.RetailEstimate;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import com.fixbridge.auth.AuthUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;
    private final PropertyRepository properties;
    private final AiService aiService;
    private final AiAssessmentRepository assessments;
    private final PricingEngine pricingEngine;
    private final JobPricingRepository jobPricing;
    private final JobStatusHistoryRepository statusHistory;

    public JobService(JobRepository jobs, PropertyRepository properties, AiService aiService,
                      AiAssessmentRepository assessments, PricingEngine pricingEngine,
                      JobPricingRepository jobPricing, JobStatusHistoryRepository statusHistory) {
        this.jobs = jobs;
        this.properties = properties;
        this.aiService = aiService;
        this.assessments = assessments;
        this.pricingEngine = pricingEngine;
        this.jobPricing = jobPricing;
        this.statusHistory = statusHistory;
    }

    /** Customer reports an issue → AI assessment → server-side retail estimate. */
    @Transactional
    public JobDtos.JobDetailView reportIssue(AuthUser user, JobDtos.ReportIssueRequest req) {
        Property property = properties.findById(req.propertyId())
                .orElseThrow(() -> ApiException.notFound("Property"));
        if (!property.getOwnerId().equals(user.id())) {
            throw ApiException.forbidden();
        }

        Job job = new Job();
        job.setCustomerId(user.id());
        job.setPropertyId(property.getId());
        job.setTitle(req.title());
        job.setDescription(req.description());
        job.setPreferredTime(req.preferredTime());
        job.setPartnerCode(req.partnerCode());
        job.setStatus(JobStatus.draft);
        job = jobs.save(job);
        recordStatus(job.getId(), null, JobStatus.draft, user.id());

        // AI assessment (structured, safety-gated) — never sets price.
        AssessmentResult assessment = aiService.assessAndStore(
                job.getId(), req.description(), req.mediaKeys() == null ? List.of() : req.mediaKeys());

        // Server-side pricing engine computes the customer retail range (or withholds it).
        RetailEstimate estimate = pricingEngine.preliminaryEstimate(assessment);
        persistPricing(job.getId(), assessment, estimate);

        transition(job, JobStatus.ai_review_complete, user.id());
        transition(job, JobStatus.awaiting_service_payment, user.id());

        return toDetail(job, assessment, estimate);
    }

    @Transactional(readOnly = true)
    public List<JobDtos.JobSummaryView> listForCustomer(AuthUser user) {
        return jobs.findByCustomerId(user.id()).stream()
                .map(j -> new JobDtos.JobSummaryView(j.getId(), j.getStatus(), j.getTitle(), j.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public JobDtos.JobDetailView getForCustomer(AuthUser user, UUID jobId) {
        Job job = requireJob(jobId);
        boolean isOwner = job.getCustomerId().equals(user.id());
        if (!isOwner && !user.hasRole(UserRole.admin)) {
            throw ApiException.forbidden();
        }
        AiAssessmentEntity a = assessments.findFirstByJobIdOrderByCreatedAtDesc(jobId).orElse(null);
        JobPricing p = jobPricing.findByJobId(jobId).orElse(null);
        return toDetailFromStored(job, a, p);
    }

    // ---- internals ----

    public Job requireJob(UUID jobId) {
        return jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
    }

    /** Records history and moves the job to a new status. */
    @Transactional
    public void transition(Job job, JobStatus to, UUID actorId) {
        JobStatus from = job.getStatus();
        job.setStatus(to);
        jobs.save(job);
        recordStatus(job.getId(), from, to, actorId);
    }

    private void recordStatus(UUID jobId, JobStatus from, JobStatus to, UUID actorId) {
        statusHistory.save(new JobStatusHistory(jobId, from, to, actorId));
    }

    private void persistPricing(UUID jobId, AssessmentResult a, RetailEstimate estimate) {
        JobPricing p = jobPricing.findByJobId(jobId).orElseGet(() -> {
            JobPricing np = new JobPricing();
            np.setJobId(jobId);
            return np;
        });
        p.setAiCategory(a.category());
        p.setAiUrgency(a.urgency());
        p.setAiConfidence(a.confidence());
        p.setEstContractorNetLow(pricingEngine.estimateNetForHours(a.estimatedLaborHoursMin()));
        p.setEstContractorNetHigh(pricingEngine.estimateNetForHours(a.estimatedLaborHoursMax()));
        if (estimate.priceAvailable()) {
            p.setCustomerRetailLow(estimate.retailLowCents());
            p.setCustomerRetailHigh(estimate.retailHighCents());
        } else {
            p.setCustomerRetailLow(null);
            p.setCustomerRetailHigh(null);
        }
        jobPricing.save(p);
    }

    private JobDtos.JobDetailView toDetail(Job job, AssessmentResult a, RetailEstimate estimate) {
        JobDtos.AssessmentView av = new JobDtos.AssessmentView(
                a.category(), a.summary(), a.urgency(), a.confidence(), a.recommendedTrade(),
                a.professionalRequired(), a.safeDiyAllowed(), a.immediateSafetySteps(), a.disclaimer());
        JobDtos.EstimateView ev = new JobDtos.EstimateView(
                estimate.priceAvailable(), estimate.retailLowCents(), estimate.retailHighCents(),
                estimate.message(), estimate.disclaimer());
        return new JobDtos.JobDetailView(job.getId(), job.getStatus(), job.getTitle(), job.getDescription(),
                job.getPreferredTime(), av, ev, job.getCreatedAt());
    }

    private JobDtos.JobDetailView toDetailFromStored(Job job, AiAssessmentEntity a, JobPricing p) {
        JobDtos.AssessmentView av = a == null ? null : new JobDtos.AssessmentView(
                a.getCategory(), a.getSummary(), a.getUrgency(), a.getConfidence(), a.getRecommendedTrade(),
                Boolean.TRUE.equals(a.getProfessionalRequired()),
                Boolean.TRUE.equals(a.getSafeDiyAllowed()),
                List.of(), // safety steps live in raw_json; omitted from the summary view
                "AI-assisted assessment, not a professional diagnosis.");
        JobDtos.EstimateView ev;
        if (p != null && p.isPriceAvailable()) {
            ev = new JobDtos.EstimateView(true, p.getCustomerRetailLow(), p.getCustomerRetailHigh(),
                    "Estimated service range.",
                    "Preliminary estimate including coordination and service delivery. Not a binding quote.");
        } else {
            ev = new JobDtos.EstimateView(false, null, null,
                    "On-site assessment required before pricing.",
                    "A verified professional will confirm pricing after assessing on site.");
        }
        return new JobDtos.JobDetailView(job.getId(), job.getStatus(), job.getTitle(), job.getDescription(),
                job.getPreferredTime(), av, ev, job.getCreatedAt());
    }

    // Expose confidence threshold helper for other services if needed
    static boolean confident(BigDecimal confidence) {
        return confidence != null && confidence.doubleValue() >= 0.5;
    }
}
