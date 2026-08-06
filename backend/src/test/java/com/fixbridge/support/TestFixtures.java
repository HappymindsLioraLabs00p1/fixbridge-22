package com.fixbridge.support;

import com.fixbridge.ai.AssessmentResult;
import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.Complexity;
import com.fixbridge.config.FixBridgeProperties;

import java.math.BigDecimal;
import java.util.List;

/** Shared builders for tests. */
public final class TestFixtures {

    private TestFixtures() {}

    public static FixBridgeProperties props() {
        var openai = new FixBridgeProperties.Ai.Provider("https://api.openai.com/v1", "", "gpt-test");
        var claude = new FixBridgeProperties.Ai.Provider("https://api.anthropic.com/v1", "", "claude-test");
        var ai = new FixBridgeProperties.Ai("openai", openai, claude, true);
        var security = new FixBridgeProperties.Security(
                "test-secret-test-secret-test-secret-1234", 15, 14, "http://localhost:3000");
        var brand = new FixBridgeProperties.Brand("FixBridge", "support@example.com", "example.com", "#1f6feb");
        var stripe = new FixBridgeProperties.Stripe("", "", "http://localhost:3000/ok",
                "http://localhost:3000/cancel", "http://localhost:3000/return", "http://localhost:3000/refresh");
        var storage = new FixBridgeProperties.Storage("bucket", 15, "http://localhost:8080");
        var twilio = new FixBridgeProperties.Twilio("", "", "");
        var resend = new FixBridgeProperties.Resend("", "notifications@example.com");
        var billing = new FixBridgeProperties.Billing(java.util.Map.of("diy_plus", "price_test"));
        return new FixBridgeProperties(brand, security, ai, stripe, storage, twilio, resend, billing);
    }

    public static AssessmentResult assessment(String category, AiUrgency urgency, double confidence,
                                              boolean safeDiy, double hoursMin, double hoursMax) {
        return new AssessmentResult(
                category, "summary", urgency, BigDecimal.valueOf(confidence), "trade",
                !safeDiy, safeDiy, List.of(), List.of(),
                BigDecimal.valueOf(hoursMin), BigDecimal.valueOf(hoursMax), Complexity.medium,
                List.of(), AssessmentResult.DEFAULT_DISCLAIMER);
    }
}
