package com.fixbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * All FixBridge application configuration, bound from {@code fixbridge.*} in application.yml
 * (which in turn reads environment variables). Brand identity, AI model names, pricing and provider
 * keys are configuration — never hard-coded — so staging/production stay separate and the platform
 * can be rebranded in one place.
 */
@ConfigurationProperties(prefix = "fixbridge")
public record FixBridgeProperties(
        Brand brand,
        Security security,
        Ai ai,
        AiService aiService,
        Stripe stripe,
        Storage storage,
        Twilio twilio,
        Resend resend,
        Notifications notifications,
        Billing billing
) {
    public record Brand(String name, String supportEmail, String domain, String primaryColor) {}

    public record Security(
            String jwtSecret,
            long accessTokenTtlMinutes,
            long refreshTokenTtlDays,
            String corsAllowedOrigins
    ) {}

    /**
     * AI provider config. {@code provider} selects one of openai | claude | openrouter.
     * OpenRouter is an aggregator that speaks the OpenAI Chat Completions API and fronts many
     * models, so its model names are namespaced (e.g. {@code anthropic/claude-sonnet-4}).
     */
    public record Ai(String provider, Provider openai, Provider claude, Provider openrouter,
                     String appUrl, String appTitle,
                     /** Ask the model to conform to our JSON schema. Turn off for models that reject it. */
                     boolean structuredOutputs,
                     /** Send reasoning:{enabled:true} — only meaningful for reasoning models. */
                     boolean reasoning,
                     boolean stubMode) {
        public record Provider(String baseUrl, String apiKey, String model) {}
    }

    public record Stripe(
            String secretKey,
            String webhookSecret,
            String successUrl,
            String cancelUrl,
            String connectReturnUrl,
            String connectRefreshUrl,
            /** Stub payments independently of the other integrations. */
            boolean stubMode
    ) {}

    /** Connection details for the separate Python AI/image service. */
    public record AiService(String baseUrl, String authToken, String model, long timeoutSeconds) {}

    /** Grouping for the SMS/email stub switch so notifications can go live on their own. */
    public record Notifications(boolean stubMode) {}

    public record Storage(String bucket, long signedUrlTtlMinutes, String publicBaseUrl,
                          /** Stub storage (local object store) independently of the rest. */
                          boolean stubMode) {}

    public record Twilio(String accountSid, String authToken, String fromNumber) {}

    public record Resend(String apiKey, String fromEmail) {}

    /** Maps a plan code → its Stripe recurring Price ID. Amounts live in Stripe, never hard-coded here. */
    public record Billing(Map<String, String> plans) {
        public Billing {
            plans = plans == null ? Map.of() : plans;
        }
    }
}
