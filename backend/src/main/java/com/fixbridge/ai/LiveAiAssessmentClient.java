package com.fixbridge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Live AI assessment via the OpenAI Responses API or the Anthropic Claude Messages API (chosen by
 * {@code fixbridge.ai.provider}). Active only when {@code fixbridge.ai.stub-mode=false}. Both providers
 * are asked for STRUCTURED output matching {@link AssessmentJson#schema()} — the AI classifies only and
 * never sets price. (Image input via GCS signed URLs is a follow-up; this sends the text description.)
 */
@Component
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "false")
public class LiveAiAssessmentClient implements AiAssessmentClient {

    private static final Logger log = LoggerFactory.getLogger(LiveAiAssessmentClient.class);

    private static final String SYSTEM = """
            You are a property-maintenance triage assistant. Analyze the reported issue and return a
            structured technical assessment ONLY. Do not provide a price. Classify category, urgency,
            recommended trade, whether a professional is required, and whether DIY is safe. Block DIY for
            gas, electrical, flooding/sewage, fire/CO, structural, roof, or hazardous-material risks.""";

    private final FixBridgeProperties.Ai cfg;
    private final WebClient openai;
    private final WebClient claude;

    public LiveAiAssessmentClient(FixBridgeProperties props) {
        this.cfg = props.ai();
        this.openai = WebClient.builder()
                .baseUrl(cfg.openai().baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.openai().apiKey())
                .build();
        this.claude = WebClient.builder()
                .baseUrl(cfg.claude().baseUrl())
                .defaultHeader("x-api-key", cfg.claude().apiKey())
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Override
    public AssessmentResult assess(String description, List<String> mediaKeys) {
        String prompt = SYSTEM + "\n\nReported issue: " + (description == null ? "" : description)
                + "\nImages attached: " + (mediaKeys == null ? 0 : mediaKeys.size());
        try {
            return "claude".equalsIgnoreCase(cfg.provider()) ? callClaude(prompt) : callOpenAi(prompt);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI assessment failed via {}: {}", cfg.provider(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The assessment service is temporarily unavailable. Please retry or request a professional.");
        }
    }

    private AssessmentResult callOpenAi(String prompt) {
        Map<String, Object> body = Map.of(
                "model", cfg.openai().model(),
                "input", prompt,
                "text", Map.of("format", Map.of(
                        "type", "json_schema",
                        "name", "assessment",
                        "strict", true,
                        "schema", AssessmentJson.schema())));
        JsonNode root = openai.post().uri("/responses")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return AssessmentJson.parse(extractOpenAiJson(root));
    }

    private AssessmentResult callClaude(String prompt) {
        Map<String, Object> body = Map.of(
                "model", cfg.claude().model(),
                "max_tokens", 1024,
                "tools", List.of(Map.of(
                        "name", "assessment",
                        "description", "Return the structured property-maintenance assessment.",
                        "input_schema", AssessmentJson.schema())),
                "tool_choice", Map.of("type", "tool", "name", "assessment"),
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        JsonNode root = claude.post().uri("/messages")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return AssessmentJson.parse(extractClaudeToolInput(root));
    }

    /** Responses API: find the first output_text content node and parse its JSON text. */
    private JsonNode extractOpenAiJson(JsonNode root) {
        if (root != null) {
            for (JsonNode item : root.path("output")) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asText())) {
                        try {
                            return new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(content.path("text").asText());
                        } catch (Exception ignored) {
                            // fall through to error below
                        }
                    }
                }
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected assessment response.");
    }

    /** Messages API: find the tool_use content block and return its structured input object. */
    private JsonNode extractClaudeToolInput(JsonNode root) {
        if (root != null) {
            for (JsonNode content : root.path("content")) {
                if ("tool_use".equals(content.path("type").asText())) {
                    return content.path("input");
                }
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected assessment response.");
    }

    @Override
    public String provider() {
        return cfg.provider();
    }

    @Override
    public String model() {
        return "claude".equalsIgnoreCase(cfg.provider()) ? cfg.claude().model() : cfg.openai().model();
    }
}
