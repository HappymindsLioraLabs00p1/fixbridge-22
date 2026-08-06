package com.fixbridge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.Complexity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared JSON Schema for the structured AI assessment (spec §10.1) and a defensive parser from the
 * model's JSON into {@link AssessmentResult}. Used by both the OpenAI and Claude live clients.
 */
public final class AssessmentJson {

    private AssessmentJson() {}

    /** JSON Schema object — used as OpenAI structured-output schema and Claude tool input_schema. */
    public static Map<String, Object> schema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("category", str());
        props.put("summary", str());
        props.put("urgency", strEnum("low", "medium", "high", "emergency"));
        props.put("confidence", Map.of("type", "number"));
        props.put("recommended_trade", str());
        props.put("professional_required", Map.of("type", "boolean"));
        props.put("safe_diy_allowed", Map.of("type", "boolean"));
        props.put("immediate_safety_steps", arr());
        props.put("visual_findings", arr());
        props.put("estimated_labor_hours_min", Map.of("type", "number"));
        props.put("estimated_labor_hours_max", Map.of("type", "number"));
        props.put("complexity", strEnum("low", "medium", "high"));
        props.put("questions_needed", arr());
        props.put("disclaimer", str());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", new ArrayList<>(props.keySet()));
        schema.put("additionalProperties", false);
        return schema;
    }

    public static AssessmentResult parse(JsonNode n) {
        return new AssessmentResult(
                text(n, "category", "general"),
                text(n, "summary", ""),
                enumOf(n, "urgency", AiUrgency.class, AiUrgency.medium),
                BigDecimal.valueOf(n.path("confidence").asDouble(0.5)),
                text(n, "recommended_trade", "professional"),
                n.path("professional_required").asBoolean(true),
                n.path("safe_diy_allowed").asBoolean(false),
                list(n, "immediate_safety_steps"),
                list(n, "visual_findings"),
                BigDecimal.valueOf(n.path("estimated_labor_hours_min").asDouble(1)),
                BigDecimal.valueOf(n.path("estimated_labor_hours_max").asDouble(3)),
                enumOf(n, "complexity", Complexity.class, Complexity.medium),
                list(n, "questions_needed"),
                text(n, "disclaimer", AssessmentResult.DEFAULT_DISCLAIMER));
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> arr() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }

    private static Map<String, Object> strEnum(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asText(def);
    }

    private static List<String> list(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = n.get(field);
        if (arr != null && arr.isArray()) {
            arr.forEach(e -> out.add(e.asText()));
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOf(JsonNode n, String field, Class<E> type, E def) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return def;
        try {
            return Enum.valueOf(type, v.asText().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }
}
