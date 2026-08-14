package com.fixbridge.ai;

import com.fixbridge.common.enums.AssessmentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates AI assessment: runs the provider client, enforces the DIY safety rules (spec §10.2),
 * and persists the structured result. On failure it logs and rethrows a safe error — the caller shows
 * a retry + professional-request option and never a raw error, and never an empty plan.
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiAssessmentClient client;
    private final AiAssessmentRepository repository;
    private final ObjectMapper objectMapper;
    private final com.fixbridge.storage.StorageService storage;

    public AiService(AiAssessmentClient client, AiAssessmentRepository repository, ObjectMapper objectMapper,
                     com.fixbridge.storage.StorageService storage) {
        this.client = client;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.storage = storage;
    }

    /** Assess an issue and persist the structured result against the job. */
    public AssessmentResult assessAndStore(UUID jobId, String description, List<String> mediaKeys) {
        // Resolve image object keys to short-lived signed URLs the AI provider can fetch.
        List<String> imageUrls = (mediaKeys == null ? List.<String>of() : mediaKeys).stream()
                .filter(AiService::isImage)
                .map(storage::createDownloadUrl)
                .toList();
        AssessmentResult result;
        try {
            result = client.assess(description, imageUrls);
        } catch (PythonAiAssessmentClient.AiServiceUnavailableException e) {
            // The assessment service is down. Recording the issue matters more than assessing it
            // immediately — the customer's report is kept and the assessment is retried, rather
            // than the whole request failing.
            log.warn("Assessment service unavailable for job {} — storing as pending: {}",
                    jobId, e.getMessage());
            savePending(jobId, description, e.getMessage());
            return AssessmentResult.pending();
        }
        result = enforceSafety(result);

        AiAssessmentEntity entity = new AiAssessmentEntity();
        entity.setJobId(jobId);
        entity.setProvider(client.provider());
        entity.setModel(client.model());
        entity.setCategory(result.category());
        entity.setSummary(result.summary());
        entity.setUrgency(result.urgency());
        entity.setConfidence(result.confidence());
        entity.setRecommendedTrade(result.recommendedTrade());
        entity.setProfessionalRequired(result.professionalRequired());
        entity.setSafeDiyAllowed(result.safeDiyAllowed());
        entity.setComplexity(result.complexity());
        entity.setLaborHoursMin(result.estimatedLaborHoursMin());
        entity.setLaborHoursMax(result.estimatedLaborHoursMax());
        entity.setRawJson(objectMapper.convertValue(result, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        repository.save(entity);
        return result;
    }

    /** Record that the job still needs assessing, so a retry can pick it up. */
    private void savePending(UUID jobId, String description, String error) {
        AiAssessmentEntity entity = new AiAssessmentEntity();
        entity.setJobId(jobId);
        entity.setProvider(client.provider());
        entity.setModel(client.model());
        entity.setStatus(AssessmentStatus.pending);
        entity.setLastError(error);
        entity.setLastAttemptAt(java.time.Instant.now());
        entity.setAttempts(1);
        // Nothing was assessed, so DIY must not be permitted by omission.
        entity.setProfessionalRequired(true);
        entity.setSafeDiyAllowed(false);
        entity.setRawJson(Map.of("pending", true, "description", description));
        repository.save(entity);
    }

    /**
     * Assessments still waiting on the service, oldest first. A scheduled sweep or an admin action
     * can re-run these; nothing here retries on its own, so a prolonged outage doesn't turn into a
     * retry storm.
     */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<AiAssessmentEntity> retryable(int limit) {
        return repository.findByStatusOrderByLastAttemptAtAsc(AssessmentStatus.pending,
                org.springframework.data.domain.PageRequest.of(0, limit));
    }

    private static boolean isImage(String key) {
        String k = key == null ? "" : key.toLowerCase();
        return k.endsWith(".jpg") || k.endsWith(".jpeg") || k.endsWith(".png") || k.endsWith(".webp");
    }

    /**
     * DIY is blocked for dangerous work regardless of what the model returned. This is a hard rule,
     * enforced server-side.
     */
    private AssessmentResult enforceSafety(AssessmentResult r) {
        boolean lowConfidence = r.confidence() != null && r.confidence().doubleValue() < 0.5;
        boolean dangerousUrgency = r.urgency() == com.fixbridge.common.enums.AiUrgency.emergency;
        if (r.safeDiyAllowed() && (lowConfidence || dangerousUrgency)) {
            log.info("Overriding safe_diy_allowed to false for a dangerous/low-confidence assessment");
            return new AssessmentResult(
                    r.category(), r.summary(), r.urgency(), r.confidence(), r.recommendedTrade(),
                    true, false, r.immediateSafetySteps(), r.visualFindings(),
                    r.estimatedLaborHoursMin(), r.estimatedLaborHoursMax(), r.complexity(),
                    r.questionsNeeded(), r.disclaimer());
        }
        return r;
    }
}
