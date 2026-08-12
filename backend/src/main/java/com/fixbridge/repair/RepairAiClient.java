package com.fixbridge.repair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixbridge.common.enums.RepairState;
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

    /**
     * Advance the conversation by one turn.
     *
     * <p>The current state travels with the request: the AI service holds nothing between turns, so
     * without it the state machine restarts at NEW every time and can never observe the transitions
     * it exists to police.
     */
    public JsonNode converse(List<Map<String, Object>> messages, RepairState currentState) {
        return post("/v1/repair/converse", Map.of(
                "messages", messages,
                "current_state", (currentState == null ? RepairState.NEW : currentState).name()));
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

    /**
     * How many times a single turn may be attempted.
     *
     * <p>Two, not more. The AI service is hosted on a tier that suspends when idle, so the first
     * request after a quiet period lands on a container that is still starting and fails while
     * waking it. The second almost always succeeds, because the first one did the waking.
     *
     * <p>Retrying was worth adding because the failure is not random: it is the predictable cost of
     * a sleeping service, and the customer was paying it as "I'm having trouble thinking right now"
     * on the first message of every session. More than one retry would turn a genuine outage into a
     * long silent wait, which is worse than an honest message.
     */
    private static final int MAX_ATTEMPTS = 2;

    private JsonNode post(String path, Map<String, Object> body) {
        RuntimeException last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
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
                if (attempt > 1) {
                    log.info("Repair AI call to {} succeeded on attempt {} — the service was cold",
                            path, attempt);
                }
                return result;
            } catch (Exception e) {
                last = e instanceof RepairAiUnavailableException r
                        ? r : new RepairAiUnavailableException(e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    // Logged at info: a first-attempt failure against a sleeping service is
                    // expected, and logging it as a warning would bury the ones that matter.
                    log.info("Repair AI call to {} failed on attempt {}, retrying: {}",
                            path, attempt, e.getMessage());
                } else {
                    log.warn("Repair AI call to {} failed after {} attempts: {}",
                            path, MAX_ATTEMPTS, e.getMessage());
                }
            }
        }
        throw last;
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
