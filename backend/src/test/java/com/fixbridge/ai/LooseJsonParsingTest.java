package com.fixbridge.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reasoning models narrate before they answer and often fence their output, so the assessment
 * parser has to find the JSON inside a reply rather than assume the reply IS the JSON.
 */
class LooseJsonParsingTest {

    @Test
    void parsesABareJsonObject() {
        var node = LiveAiAssessmentClient.parseLoosely("{\"category\":\"plumbing\",\"urgency\":\"high\"}");
        assertThat(node).isNotNull();
        assertThat(node.get("category").asText()).isEqualTo("plumbing");
    }

    @Test
    void parsesJsonWrappedInAMarkdownFence() {
        String raw = """
                ```json
                {"category":"electrical","urgency":"emergency"}
                ```
                """;
        var node = LiveAiAssessmentClient.parseLoosely(raw);
        assertThat(node).isNotNull();
        assertThat(node.get("urgency").asText()).isEqualTo("emergency");
    }

    @Test
    void findsJsonAfterAModelNarratesItsReasoning() {
        String raw = """
                Let me think about this. The customer describes water pooling under a sink,
                which suggests a failed trap or supply line. Here is my assessment:

                {"category":"plumbing","urgency":"high","safe_diy_allowed":false}

                I hope that helps.
                """;
        var node = LiveAiAssessmentClient.parseLoosely(raw);
        assertThat(node).isNotNull();
        assertThat(node.get("safe_diy_allowed").asBoolean()).isFalse();
    }

    @Test
    void isNotConfusedByBracesInsideStrings() {
        String raw = "Note: use {braces} carefully. {\"category\":\"handyman\",\"summary\":\"a { in text\"}";
        var node = LiveAiAssessmentClient.parseLoosely(raw);
        assertThat(node).isNotNull();
        assertThat(node.get("category").asText()).isEqualTo("handyman");
    }

    @Test
    void returnsNullWhenThereIsNoJsonAtAll() {
        assertThat(LiveAiAssessmentClient.parseLoosely("I'm not sure how to answer that.")).isNull();
        assertThat(LiveAiAssessmentClient.parseLoosely("")).isNull();
        assertThat(LiveAiAssessmentClient.parseLoosely(null)).isNull();
    }
}
