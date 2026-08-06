package com.fixbridge.payment;

import com.fixbridge.common.enums.PaymentType;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Account;
import com.stripe.model.AccountLink;
import com.stripe.model.Transfer;
import com.stripe.model.checkout.Session;
import com.stripe.param.AccountCreateParams;
import com.stripe.param.AccountLinkCreateParams;
import com.stripe.param.TransferCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Real Stripe implementation (SDK), active only when {@code fixbridge.ai.stub-mode=false}. Uses SEPARATE
 * CHARGES AND TRANSFERS: customers are charged via Checkout on the platform account; contractors are paid
 * by an explicit Connect transfer after completion. Onboarding uses Stripe-hosted Connect (Express).
 */
@Component
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "false")
public class LiveStripeClient implements StripeClient {

    private static final Logger log = LoggerFactory.getLogger(LiveStripeClient.class);

    private final FixBridgeProperties.Stripe cfg;

    public LiveStripeClient(FixBridgeProperties props) {
        this.cfg = props.stripe();
        Stripe.apiKey = cfg.secretKey();
    }

    @Override
    public CheckoutSession createCheckout(PaymentType type, long amountCents, String currency, String referenceId) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(cfg.successUrl())
                    .setCancelUrl(cfg.cancelUrl())
                    .setClientReferenceId(referenceId)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                    .setCurrency(currency.toLowerCase())
                                    .setUnitAmount(amountCents)
                                    .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                            .setName(productName(type))
                                            .build())
                                    .build())
                            .build())
                    .build();
            Session session = Session.create(params);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw paymentError("create checkout", e);
        }
    }

    @Override
    public String createTransfer(String connectedAccountId, long amountCents, String currency, String referenceId) {
        try {
            Transfer transfer = Transfer.create(TransferCreateParams.builder()
                    .setAmount(amountCents)
                    .setCurrency(currency.toLowerCase())
                    .setDestination(connectedAccountId)
                    .putMetadata("reference", referenceId)
                    .build());
            return transfer.getId();
        } catch (StripeException e) {
            throw paymentError("create transfer", e);
        }
    }

    @Override
    public CheckoutSession createSubscriptionCheckout(String customerEmail, String priceId, String referenceId) {
        try {
            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                    .setSuccessUrl(cfg.successUrl())
                    .setCancelUrl(cfg.cancelUrl())
                    .setClientReferenceId(referenceId)
                    .setCustomerEmail(customerEmail)
                    .addLineItem(SessionCreateParams.LineItem.builder()
                            .setQuantity(1L)
                            .setPrice(priceId)
                            .build())
                    .build();
            Session session = Session.create(params);
            return new CheckoutSession(session.getId(), session.getUrl());
        } catch (StripeException e) {
            throw paymentError("create subscription checkout", e);
        }
    }

    @Override
    public ConnectAccount createConnectAccount(String email) {
        try {
            Account account = Account.create(AccountCreateParams.builder()
                    .setType(AccountCreateParams.Type.EXPRESS)
                    .setEmail(email)
                    .build());
            AccountLink link = AccountLink.create(AccountLinkCreateParams.builder()
                    .setAccount(account.getId())
                    .setRefreshUrl(cfg.connectRefreshUrl())
                    .setReturnUrl(cfg.connectReturnUrl())
                    .setType(AccountLinkCreateParams.Type.ACCOUNT_ONBOARDING)
                    .build());
            return new ConnectAccount(account.getId(), link.getUrl());
        } catch (StripeException e) {
            throw paymentError("create connect account", e);
        }
    }

    private String productName(PaymentType type) {
        return switch (type) {
            case dispatch_fee -> "Service Assessment & Dispatch";
            case managed_repair, deposit, progress, final_payment -> "Repair service";
            case lead_fee -> "Lead unlock";
            case subscription -> "Subscription";
        };
    }

    private ApiException paymentError(String action, StripeException e) {
        log.error("Stripe error during {}: {}", action, e.getMessage());
        return new ApiException(HttpStatus.BAD_GATEWAY, "Payment provider error. Please try again.");
    }
}
