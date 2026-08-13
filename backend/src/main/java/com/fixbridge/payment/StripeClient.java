package com.fixbridge.payment;

import com.fixbridge.common.enums.PaymentType;

/**
 * Abstraction over Stripe. Managed jobs use SEPARATE CHARGES AND TRANSFERS: the customer is charged on
 * the platform account; the contractor is paid by an explicit transfer only after completion approval.
 * A stub implementation is used in the frontend-first phase. All amounts are computed server-side.
 */
public interface StripeClient {

    /** A hosted Checkout session for a customer charge. */
    record CheckoutSession(String sessionId, String url) {}

    CheckoutSession createCheckout(PaymentType type, long amountCents, String currency, String referenceId);

    /** Create a Connect transfer to a contractor's connected account. Returns the transfer id. */
    String createTransfer(String connectedAccountId, long amountCents, String currency, String referenceId);

    /** A newly created Connect account plus the hosted onboarding link the contractor is sent to. */
    record ConnectAccount(String accountId, String onboardingUrl) {}

    /** Create a Stripe-hosted Connect onboarding flow (no custom bank/identity form of our own). */
    ConnectAccount createConnectAccount(String email);

    /** Create a Checkout session in subscription mode for a recurring Price. */
    CheckoutSession createSubscriptionCheckout(String customerEmail, String priceId, String referenceId);

    /** Refund a captured payment (full or partial). Returns the refund id. */
    String createRefund(String paymentIntentId, long amountCents, String reason);

    /** A hold placed on a card: money reserved, not taken. */
    record Authorization(String paymentIntentId, String clientSecret, long amountCents) {}

    /**
     * Reserve the contractor's visit fee without taking it.
     *
     * <p>A hold rather than a charge because at this point nobody has agreed to do the work. If no
     * contractor accepts, the homeowner must end up having paid nothing — and a refund is not the
     * same as never having been charged: it takes days to land, and it looks like a mistake on a
     * statement. The money is captured only when a contractor accepts.
     */
    Authorization authorize(long amountCents, String currency, String referenceId);

    /**
     * Take money previously reserved. Called when a contractor accepts the job.
     *
     * <p>The amount may be lower than the authorisation but never higher: a homeowner agreed to a
     * specific figure, and capturing more than they saw is indefensible whatever the reason.
     */
    void captureAuthorization(String paymentIntentId, long amountCents);

    /**
     * Release a hold, returning the reserved funds. Called when no contractor accepts, or the
     * homeowner cancels before one does.
     */
    void releaseAuthorization(String paymentIntentId, String reason);
}
