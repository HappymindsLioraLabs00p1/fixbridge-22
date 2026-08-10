package com.fixbridge.ai;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.Complexity;
import com.fixbridge.config.FixBridgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic stub used when {@code fixbridge.ai.stub-mode=true} (frontend-first phase — no API keys
 * needed). It keyword-classifies the description and, importantly, exercises the SAFETY gating:
 * dangerous issues block DIY and require a professional. Real {@code OpenAiResponsesClient} /
 * {@code ClaudeClient} implementations replace this without changing any caller.
 */
@Component
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "true", matchIfMissing = true)
// Stand aside when the separate Python service is the configured provider (AI_PROVIDER=python).
@ConditionalOnExpression("'${fixbridge.ai.provider:openai}' != 'python'")
public class StubAiAssessmentClient implements AiAssessmentClient {

    private static final List<String> DANGER_KEYWORDS = List.of(
            "gas", "smoke", "fire", "carbon monoxide", "spark", "shock", "sewage", "flood",
            "structural", "collapse", "asbestos", "lead", "high voltage");

    private final FixBridgeProperties props;

    public StubAiAssessmentClient(FixBridgeProperties props) {
        this.props = props;
    }

    @Override
    public AssessmentResult assess(String description, List<String> mediaKeys) {
        String text = description == null ? "" : description.toLowerCase(Locale.ROOT);
        boolean dangerous = DANGER_KEYWORDS.stream().anyMatch(text::contains);
        boolean leak = text.contains("leak") || text.contains("water") || text.contains("pipe");
        boolean electrical = text.contains("electric") || text.contains("outlet") || text.contains("breaker");

        if (dangerous) {
            return new AssessmentResult(
                    electrical ? "electrical" : "safety",
                    "Potential safety hazard detected. On-site professional assessment required.",
                    AiUrgency.emergency,
                    new BigDecimal("0.55"),
                    electrical ? "licensed_electrician" : "licensed_professional",
                    true,
                    false,
                    List.of("Keep clear of the area.", "If it is safe, shut off the relevant utility.",
                            "Call emergency services if there is immediate danger."),
                    List.of("Possible hazardous condition from description."),
                    new BigDecimal("1"), new BigDecimal("4"),
                    Complexity.high,
                    List.of(),
                    AssessmentResult.DEFAULT_DISCLAIMER);
        }

        if (leak) {
            return new AssessmentResult(
                    "plumbing",
                    "Possible active leak at a pipe or fixture joint.",
                    AiUrgency.high,
                    new BigDecimal("0.82"),
                    "licensed_plumber",
                    true,
                    false,
                    List.of("Shut off the water supply if it is safe to do so."),
                    List.of("Signs of water escape described."),
                    new BigDecimal("1"), new BigDecimal("3"),
                    Complexity.medium,
                    List.of(),
                    AssessmentResult.DEFAULT_DISCLAIMER);
        }

        // Default: a minor handyman issue that is generally safe to DIY.
        return new AssessmentResult(
                "handyman",
                "Minor maintenance issue suitable for a standard repair visit.",
                AiUrgency.low,
                new BigDecimal("0.78"),
                "handyman",
                false,
                true,
                List.of(),
                List.of("No obvious hazard described."),
                new BigDecimal("0.5"), new BigDecimal("2"),
                Complexity.low,
                List.of("Can you share a clearer photo of the affected area?"),
                AssessmentResult.DEFAULT_DISCLAIMER);
    }

    @Override
    public String provider() {
        return "stub-" + props.ai().provider();
    }

    @Override
    public String model() {
        var ai = props.ai();
        return switch (ai.provider() == null ? "openai" : ai.provider().toLowerCase()) {
            case "claude" -> ai.claude().model();
            case "openrouter" -> ai.openrouter().model();
            default -> ai.openai().model();
        };
    }
}
