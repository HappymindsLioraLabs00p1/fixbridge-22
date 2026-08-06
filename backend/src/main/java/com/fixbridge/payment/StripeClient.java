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
}
