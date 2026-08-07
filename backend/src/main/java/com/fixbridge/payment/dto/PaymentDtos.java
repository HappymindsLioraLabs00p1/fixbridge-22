package com.fixbridge.payment.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {}

    public record DispatchCheckoutRequest(@NotBlank String serviceType) {}

    /** Returned to the client, which redirects the customer to {@code url} (Stripe Checkout). */
    public record CheckoutView(String sessionId, String url, long amountCents, String currency) {}

    public record PayoutView(UUID transferId, long amountCents, String status) {}

    /** A customer payment with what's already been refunded against it (admin view). */
    public record PaymentView(
            UUID id,
            String type,
            String status,
            long amountCents,
            long refundedCents,
            long refundableCents,
            boolean disputed,
            java.time.Instant createdAt
    ) {}

    public record RefundRequest(
            @jakarta.validation.constraints.Positive long amountCents,
            String reason
    ) {}

    public record RefundView(UUID id, UUID paymentId, long amountCents, String reason, java.time.Instant createdAt) {}

    public record HoldRequest(@jakarta.validation.constraints.NotBlank String reason) {}

    public record PayoutHoldView(UUID jobId, boolean held, String reason) {}
}
