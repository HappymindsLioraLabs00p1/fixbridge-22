package com.fixbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        Stripe stripe,
        Storage storage,
        Twilio twilio,
        Resend resend
) {
    public record Brand(String name, String supportEmail, String domain, String primaryColor) {}

    public record Security(
            String jwtSecret,
            long accessTokenTtlMinutes,
            long refreshTokenTtlDays,
            String corsAllowedOrigins
    ) {}

    public record Ai(String provider, Provider openai, Provider claude, boolean stubMode) {
        public record Provider(String baseUrl, String apiKey, String model) {}
    }

    public record Stripe(
            String secretKey,
            String webhookSecret,
            String successUrl,
            String cancelUrl,
            String connectReturnUrl,
            String connectRefreshUrl
    ) {}

    public record Storage(String bucket, long signedUrlTtlMinutes) {}

    public record Twilio(String accountSid, String authToken, String fromNumber) {}

    public record Resend(String apiKey, String fromEmail) {}
}
