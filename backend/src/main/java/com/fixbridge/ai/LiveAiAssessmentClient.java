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

import java.util.ArrayList;
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
    private final WebClient openrouter;

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
        this.openrouter = WebClient.builder()
                .baseUrl(cfg.openrouter().baseUrl())
                .defaultHeader("Authorization", "Bearer " + cfg.openrouter().apiKey())
                // OpenRouter uses these to attribute traffic to your app.
                .defaultHeader("HTTP-Referer", cfg.appUrl() == null ? "" : cfg.appUrl())
                .defaultHeader("X-Title", cfg.appTitle() == null ? "" : cfg.appTitle())
                .build();
    }

    @Override
    public AssessmentResult assess(String description, List<String> imageUrls) {
        List<String> images = imageUrls == null ? List.of() : imageUrls;
        String prompt = SYSTEM + "\n\nReported issue: " + (description == null ? "" : description);
        try {
            return switch (cfg.provider() == null ? "openai" : cfg.provider().toLowerCase()) {
                case "claude" -> callClaude(prompt, images);
                case "openrouter" -> callOpenRouter(prompt, images);
                default -> callOpenAi(prompt, images);
            };
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("AI assessment failed via {}: {}", cfg.provider(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The assessment service is temporarily unavailable. Please retry or request a professional.");
        }
    }

    private AssessmentResult callOpenAi(String prompt, List<String> imageUrls) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "input_text", "text", prompt));
        for (String url : imageUrls) {
            content.add(Map.of("type", "input_image", "image_url", url));
        }
        Map<String, Object> body = Map.of(
                "model", cfg.openai().model(),
                "input", List.of(Map.of("role", "user", "content", content)),
                "text", Map.of("format", Map.of(
                        "type", "json_schema",
                        "name", "assessment",
                        "strict", true,
                        "schema", AssessmentJson.schema())));
        JsonNode root = openai.post().uri("/responses")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return AssessmentJson.parse(extractOpenAiJson(root));
    }

    private AssessmentResult callClaude(String prompt, List<String> imageUrls) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", prompt));
        for (String url : imageUrls) {
            content.add(Map.of("type", "image", "source", Map.of("type", "url", "url", url)));
        }
        Map<String, Object> body = Map.of(
                "model", cfg.claude().model(),
                "max_tokens", 1024,
                "tools", List.of(Map.of(
                        "name", "assessment",
                        "description", "Return the structured property-maintenance assessment.",
                        "input_schema", AssessmentJson.schema())),
                "tool_choice", Map.of("type", "tool", "name", "assessment"),
                "messages", List.of(Map.of("role", "user", "content", content)));
        JsonNode root = claude.post().uri("/messages")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return AssessmentJson.parse(extractClaudeToolInput(root));
    }

    /**
     * OpenRouter fronts many models behind the OpenAI Chat Completions API, so this is a
     * /chat/completions call — not the Responses API used above. Structured output comes back via
     * response_format, and images ride as image_url content parts.
     */
    private AssessmentResult callOpenRouter(String prompt, List<String> imageUrls) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "text", "text", prompt));
        for (String url : imageUrls) {
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", cfg.openrouter().model());
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        if (cfg.structuredOutputs()) {
            // Ask the model to conform to the schema. Not every model honours it, which is why the
            // response is also parsed defensively.
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of("name", "assessment", "strict", true,
                            "schema", AssessmentJson.schema())));
        }
        if (cfg.reasoning()) {
            body.put("reasoning", Map.of("enabled", true));
        }
        JsonNode root = openrouter.post().uri("/chat/completions")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        return AssessmentJson.parse(extractChatCompletionJson(root));
    }

    /** Chat Completions: the structured payload arrives as JSON text in the first choice's message. */
    private JsonNode extractChatCompletionJson(JsonNode root) {
        if (root == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected assessment response.");
        }
        if (root.has("error")) {
            log.error("OpenRouter returned an error: {}", root.path("error").toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "The assessment service rejected the request. Please retry or request a professional.");
        }
        JsonNode message = root.path("choices").path(0).path("message");
        // Some models return the object directly rather than as a JSON string.
        if (message.path("content").isObject()) {
            return message.path("content");
        }
        JsonNode parsed = parseLoosely(message.path("content").asText(null));
        if (parsed != null) {
            return parsed;
        }
        log.error("Assessment response contained no parsable JSON");
        throw new ApiException(HttpStatus.BAD_GATEWAY, "Unexpected assessment response.");
    }

    /**
     * Pull a JSON object out of a model's reply. Reasoning models routinely narrate before answering
     * and wrap the payload in a ```json fence, so accepting only a bare JSON body would reject
     * perfectly good responses. Tries the whole string first, then the first balanced {...} block.
     */
    static JsonNode parseLoosely(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String text = raw.trim();

        // Strip a markdown code fence if present.
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                text = text.substring(firstNewline + 1, closing).trim();
            }
        }
        try {
            JsonNode node = mapper.readTree(text);
            if (node.isObject()) return node;
        } catch (Exception ignored) {
            // fall through to scanning for an embedded object
        }

        // Scan for the first balanced object, ignoring braces inside strings.
        int start = text.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false, escaped = false;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') { inString = !inString; continue; }
                if (inString) continue;
                if (c == '{') depth++;
                else if (c == '}' && --depth == 0) {
                    try {
                        JsonNode node = mapper.readTree(text.substring(start, i + 1));
                        if (node.isObject() && node.size() > 0) return node;
                    } catch (Exception ignored) {
                        // not valid — try the next candidate
                    }
                    break;
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
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
        return switch (cfg.provider() == null ? "openai" : cfg.provider().toLowerCase()) {
            case "claude" -> cfg.claude().model();
            case "openrouter" -> cfg.openrouter().model();
            default -> cfg.openai().model();
        };
    }
}
