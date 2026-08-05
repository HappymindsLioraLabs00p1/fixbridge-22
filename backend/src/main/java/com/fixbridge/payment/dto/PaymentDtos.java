package com.fixbridge.payment.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {}

    public record DispatchCheckoutRequest(@NotBlank String serviceType) {}

    /** Returned to the client, which redirects the customer to {@code url} (Stripe Checkout). */
    public record CheckoutView(String sessionId, String url, long amountCents, String currency) {}

    public record PayoutView(UUID transferId, long amountCents, String status) {}
}
