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
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "true", matchIfMissing = true)
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
}
