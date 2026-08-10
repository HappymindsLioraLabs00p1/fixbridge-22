package com.fixbridge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.Complexity;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delegates assessment to the Python AI/image service.
 *
 * <p>This is a third implementation of the existing {@link AiAssessmentClient} contract — no
 * business logic moves out of Java. The Python service resizes and re-encodes images before they
 * reach a model, which both reduces cost and strips EXIF/GPS metadata that would otherwise disclose
 * a customer's address to a contractor.
 *
 * <p>Selected with {@code AI_PROVIDER=python}; the OpenAI, Claude and stub clients are untouched.
 */
@Component
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "provider", havingValue = "python")
public class PythonAiAssessmentClient implements AiAssessmentClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiAssessmentClient.class);

    private final WebClient client;
    private final FixBridgeProperties.AiService cfg;

    public PythonAiAssessmentClient(FixBridgeProperties props) {
        this.cfg = props.aiService();
        this.client = WebClient.builder()
                .baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.authToken())
                .build();
    }

    @Override
    public AssessmentResult assess(String description, List<String> imageUrls) {
        Map<String, Object> body = Map.of(
                "description", description,
                "image_urls", imageUrls == null ? List.of() : imageUrls,
                "correlation_id", com.fixbridge.common.CorrelationId.current());
        try {
            JsonNode root = client.post().uri("/v1/assessment/analyze-from-url")
                    .header("X-Correlation-Id", com.fixbridge.common.CorrelationId.current())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(cfg.timeoutSeconds()));
            if (root == null) {
                throw new AiServiceUnavailableException("The assessment service returned no response");
            }
            logImageSavings(root);
            return toResult(root);
        } catch (AiServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            // Distinguish "the service is down" from "the request was wrong": only the former is
            // worth retrying, and only the former should leave the job assessable later.
            log.warn("Python AI service call failed: {}", e.getMessage());
            throw new AiServiceUnavailableException(e.getMessage());
        }
    }

    /** Records what the image pipeline saved, so cost per assessment is observable. */
    private void logImageSavings(JsonNode root) {
        JsonNode images = root.path("images");
        if (!images.isArray() || images.isEmpty()) return;
        long original = 0, processed = 0;
        int withGps = 0;
        for (JsonNode img : images) {
            original += img.path("original_bytes").asLong();
            processed += img.path("processed_bytes").asLong();
            if (img.path("had_gps").asBoolean()) withGps++;
        }
        log.info("AI images processed: {} image(s), {} KB -> {} KB, {} carried GPS metadata (removed)",
                images.size(), original / 1024, processed / 1024, withGps);
    }

    private AssessmentResult toResult(JsonNode n) {
        List<String> safety = new ArrayList<>();
        n.path("safety_notes").forEach(x -> safety.add(x.asText()));
        return new AssessmentResult(
                n.path("category").asText(null),
                n.path("assessment").asText(null),
                enumOrNull(AiUrgency.class, n.path("urgency").asText(null)),
                n.hasNonNull("confidence") ? BigDecimal.valueOf(n.path("confidence").asDouble()) : null,
                n.path("recommended_trade").asText(null),
                n.path("professional_required").asBoolean(true),
                n.path("safe_diy_allowed").asBoolean(false),
                safety,
                List.of(),
                n.hasNonNull("estimated_labor_hours_min")
                        ? BigDecimal.valueOf(n.path("estimated_labor_hours_min").asDouble()) : null,
                n.hasNonNull("estimated_labor_hours_max")
                        ? BigDecimal.valueOf(n.path("estimated_labor_hours_max").asDouble()) : null,
                enumOrNull(Complexity.class, n.path("complexity").asText(null)),
                List.of(),
                AssessmentResult.DEFAULT_DISCLAIMER);
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value.toLowerCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised {} from the assessment service: {}", type.getSimpleName(), value);
            return null;
        }
    }

    @Override
    public String provider() {
        return "python-ai-service";
    }

    @Override
    public String model() {
        return cfg.model();
    }

    /** Signals a transient failure: the job should be stored and the assessment retried. */
    public static class AiServiceUnavailableException extends RuntimeException {
        public AiServiceUnavailableException(String message) {
            super(message);
        }
    }
}
