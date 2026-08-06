package com.fixbridge.billing.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public final class BillingDtos {

    private BillingDtos() {}

    /** A subscribable plan. The recurring amount lives in Stripe and is shown at checkout. */
    public record PlanView(String code, String name, String blurb, String audience, String interval, boolean available) {}

    public record SubscribeRequest(@NotBlank String planCode) {}

    public record CheckoutView(String sessionId, String url) {}

    public record SubscriptionView(String planCode, String status, Instant currentPeriodEnd) {}
}
