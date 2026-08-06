package com.fixbridge.ai;

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
        AssessmentResult result = client.assess(description, imageUrls);
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
