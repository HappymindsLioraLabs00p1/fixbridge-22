package com.fixbridge.repair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixbridge.common.CorrelationId;
import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Calls the Python service for conversation and step verification.
 *
 * <p>Separate from {@code PythonAiAssessmentClient} because these endpoints serve a different
 * purpose: that one implements the assessment provider contract, this one drives the guided-repair
 * flow. Both talk to the same service and share its configuration.
 */
@Component
public class RepairAiClient {

    private static final Logger log = LoggerFactory.getLogger(RepairAiClient.class);

    private final WebClient client;
    private final FixBridgeProperties.AiService cfg;

    public RepairAiClient(FixBridgeProperties props) {
        this.cfg = props.aiService();
        this.client = WebClient.builder()
                .baseUrl(cfg.baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.authToken())
                // Conversation payloads carry full history; the default 256KB buffer is tight.
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    /** Advance the conversation by one turn. */
    public JsonNode converse(List<Map<String, Object>> messages) {
        return post("/v1/repair/converse", Map.of("messages", messages));
    }

    /** Check a progress photo against what the step was meant to achieve. */
    public JsonNode verifyStep(int stepNumber, String instruction, String expectedResult,
                               List<String> imageUrls) {
        return post("/v1/repair/verify-step", Map.of(
                "step_number", stepNumber,
                "instruction", instruction,
                "expected_result", expectedResult == null ? "" : expectedResult,
                "image_urls", imageUrls));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            JsonNode result = client.post().uri(path)
                    .header("X-Correlation-Id", CorrelationId.current())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(cfg.timeoutSeconds()));
            if (result == null) {
                throw new RepairAiUnavailableException("The assistant returned no response");
            }
            return result;
        } catch (RepairAiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Repair AI call to {} failed: {}", path, e.getMessage());
            throw new RepairAiUnavailableException(e.getMessage());
        }
    }

    /**
     * The assistant is unreachable. Callers turn this into a plain message rather than an error —
     * a customer mid-repair should be told to try again, not shown a stack trace.
     */
    public static class RepairAiUnavailableException extends RuntimeException {
        public RepairAiUnavailableException(String message) {
            super(message);
        }
    }
}
