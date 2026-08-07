package com.fixbridge.job;

import com.fixbridge.ai.AiAssessmentEntity;
import com.fixbridge.ai.AiAssessmentRepository;
import com.fixbridge.ai.AiService;
import com.fixbridge.ai.AssessmentResult;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
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
import java.time.Instant;
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
    private final JobMediaRepository jobMedia;
    private final com.fixbridge.storage.StorageService storage;
    private final CompletionReportRepository completionReports;
    private final ChangeOrderRepository changeOrders;

    public JobService(JobRepository jobs, PropertyRepository properties, AiService aiService,
                      AiAssessmentRepository assessments, PricingEngine pricingEngine,
                      JobPricingRepository jobPricing, JobStatusHistoryRepository statusHistory,
                      JobMediaRepository jobMedia, com.fixbridge.storage.StorageService storage,
                      CompletionReportRepository completionReports, ChangeOrderRepository changeOrders) {
        this.jobs = jobs;
        this.properties = properties;
        this.aiService = aiService;
        this.assessments = assessments;
        this.pricingEngine = pricingEngine;
        this.jobPricing = jobPricing;
        this.statusHistory = statusHistory;
        this.jobMedia = jobMedia;
        this.storage = storage;
        this.completionReports = completionReports;
        this.changeOrders = changeOrders;
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

        // Persist attached media (uploaded directly to storage; we store only the object keys).
        if (req.mediaKeys() != null) {
            for (String key : req.mediaKeys()) {
                jobMedia.save(new JobMedia(job.getId(), key, mediaTypeOf(key)));
            }
        }

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

    /** The contractor's completion proof for a job, with photos as short-lived signed URLs. */
    @Transactional(readOnly = true)
    public JobDtos.CompletionView completionFor(AuthUser user, UUID jobId) {
        Job job = requireJob(jobId);
        if (!job.getCustomerId().equals(user.id()) && !user.hasRole(UserRole.admin)) {
            throw ApiException.forbidden();
        }
        return completionReports.findFirstByJobIdOrderByCreatedAtDesc(jobId)
                .map(this::toCompletionView)
                .orElse(null);
    }

    /**
     * Customer (or admin) signs off the completed work — FR-JOB-8. Payout stays blocked until this
     * happens, and no change order may still be awaiting the customer's approval.
     */
    @Transactional
    public JobDtos.CompletionView confirmCompletion(AuthUser user, UUID jobId) {
        Job job = requireJob(jobId);
        boolean isAdmin = user.hasRole(UserRole.admin);
        if (!job.getCustomerId().equals(user.id()) && !isAdmin) {
            throw ApiException.forbidden();
        }
        CompletionReport report = completionReports.findFirstByJobIdOrderByCreatedAtDesc(jobId)
                .orElseThrow(() -> ApiException.conflict("The contractor has not submitted completion proof yet"));
        if (report.isApproved()) {
            return toCompletionView(report);
        }
        // FR-JOB-8: no unresolved change order may remain.
        boolean unresolved = changeOrders.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .anyMatch(co -> co.getStatus() == ProposalStatus.draft || co.getStatus() == ProposalStatus.sent);
        if (unresolved) {
            throw ApiException.conflict("Approve or decline the outstanding change order first");
        }

        report.setApprovedBy(user.id());
        report.setApprovedAt(Instant.now());
        completionReports.save(report);
        transition(job, JobStatus.admin_review_pending, user.id());
        return toCompletionView(report);
    }

    /** True once the customer or an admin has signed off the completion proof (payout gate). */
    @Transactional(readOnly = true)
    public boolean isCompletionApproved(UUID jobId) {
        return completionReports.findFirstByJobIdOrderByCreatedAtDesc(jobId)
                .map(CompletionReport::isApproved)
                .orElse(false);
    }

    private JobDtos.CompletionView toCompletionView(CompletionReport r) {
        return new JobDtos.CompletionView(
                r.getId(), r.getSummary(), r.getMaterialsUsed(), r.getArrivedAt(), r.getCompletedAt(),
                signed(r.getBeforeKeys()), signed(r.getAfterKeys()),
                r.getInvoiceUrl(), r.getWarrantyText(), r.isApproved(), r.getApprovedAt());
    }

    private List<String> signed(String[] keys) {
        if (keys == null) return List.of();
        return java.util.Arrays.stream(keys).filter(k -> k != null && !k.isBlank())
                .map(storage::createDownloadUrl).toList();
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
                job.getPreferredTime(), av, ev, mediaViews(job.getId()), job.getCreatedAt());
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
                job.getPreferredTime(), av, ev, mediaViews(job.getId()), job.getCreatedAt());
    }

    private List<JobDtos.MediaView> mediaViews(UUID jobId) {
        return jobMedia.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .map(m -> new JobDtos.MediaView(m.getMediaType(), storage.createDownloadUrl(m.getStorageKey())))
                .toList();
    }

    private static String mediaTypeOf(String key) {
        return key != null && key.toLowerCase().endsWith(".mp4") ? "video" : "image";
    }

    // Expose confidence threshold helper for other services if needed
    static boolean confident(BigDecimal confidence) {
        return confidence != null && confidence.doubleValue() >= 0.5;
    }
}
