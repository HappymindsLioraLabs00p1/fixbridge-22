package com.fixbridge.payment;

import com.fixbridge.common.enums.PaymentType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Deterministic Stripe stub for the frontend-first phase (no Stripe keys required). Returns fake
 * session/transfer identifiers so the full money-loop can be exercised end-to-end locally. Replaced by
 * a real {@code LiveStripeClient} (Stripe SDK) without changing callers.
 */
@Component
@ConditionalOnProperty(prefix = "fixbridge.stripe", name = "stub-mode", havingValue = "true", matchIfMissing = true)
public class StubStripeClient implements StripeClient {

    @Override
    public CheckoutSession createCheckout(PaymentType type, long amountCents, String currency, String referenceId) {
        String session = "cs_stub_" + UUID.randomUUID().toString().replace("-", "");
        String url = "https://stub.checkout.local/pay/" + session
                + "?amount=" + amountCents + "&type=" + type.name();
        return new CheckoutSession(session, url);
    }

    @Override
    public String createTransfer(String connectedAccountId, long amountCents, String currency, String referenceId) {
        return "tr_stub_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public ConnectAccount createConnectAccount(String email) {
        String acct = "acct_stub_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return new ConnectAccount(acct, "https://stub.connect.local/onboard/" + acct);
    }

    @Override
    public String createRefund(String paymentIntentId, long amountCents, String reason) {
        return "re_stub_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public CheckoutSession createSubscriptionCheckout(String customerEmail, String priceId, String referenceId) {
        String session = "cs_sub_stub_" + UUID.randomUUID().toString().replace("-", "");
        return new CheckoutSession(session, "https://stub.checkout.local/subscribe/" + session);
    }
    /**
     * Records the hold in memory so tests can assert the lifecycle — authorise, then capture or
     * release, never both, and never capture more than was held.
     */
    private final java.util.Map<String, Long> held = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Authorization authorize(long amountCents, String currency, String referenceId) {
        String intent = "pi_stub_auth_" + UUID.randomUUID().toString().replace("-", "");
        held.put(intent, amountCents);
        return new Authorization(intent, "cs_stub_secret_" + intent, amountCents);
    }

    @Override
    public void captureAuthorization(String paymentIntentId, long amountCents) {
        Long authorised = held.remove(paymentIntentId);
        if (authorised == null) {
            throw new IllegalStateException("No hold to capture: " + paymentIntentId);
        }
        if (amountCents > authorised) {
            // Capturing more than the homeowner saw and agreed to is indefensible, so the stub
            // refuses it too — a bug that only appears against live Stripe is a bug found late.
            throw new IllegalStateException(
                    "Cannot capture " + amountCents + " against a hold of " + authorised);
        }
    }

    @Override
    public void releaseAuthorization(String paymentIntentId, String reason) {
        held.remove(paymentIntentId);
    }

    /** Visible for testing: how much is still on hold. */
    public Long heldAmount(String paymentIntentId) {
        return held.get(paymentIntentId);
    }
}
