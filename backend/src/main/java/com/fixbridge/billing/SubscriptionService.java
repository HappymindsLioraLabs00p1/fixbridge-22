package com.fixbridge.billing;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.billing.dto.BillingDtos;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.payment.StripeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Subscription/recurring-billing via Stripe Billing. The plan catalog is code + copy only; the recurring
 * amount is a Stripe Price (configured via {@code fixbridge.billing.plans}), never hard-coded here.
 */
@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    /** Static plan catalog (product definitions). Prices are Stripe Price IDs from config. */
    private record Plan(String code, String name, String blurb, String audience) {}

    private static final List<Plan> CATALOG = List.of(
            new Plan("diy_plus", "DIY+", "Guided DIY plans, priority AI triage, member pricing", "Homeowner"),
            new Plan("homecare_plus", "HomeCare+", "Seasonal maintenance plan + priority dispatch", "Homeowner"),
            new Plan("property_pro", "Property Pro", "Multi-property management, budgets & reporting", "Landlord / PM"),
            new Plan("contractor_pro", "Contractor Pro", "Qualified lead access + contractor software tools", "Contractor"));

    private final SubscriptionRepository subscriptions;
    private final StripeClient stripe;
    private final FixBridgeProperties props;

    public SubscriptionService(SubscriptionRepository subscriptions, StripeClient stripe, FixBridgeProperties props) {
        this.subscriptions = subscriptions;
        this.stripe = stripe;
        this.props = props;
    }

    public List<BillingDtos.PlanView> plans() {
        Map<String, String> priceIds = props.billing().plans();
        boolean stub = props.stripe().stubMode();
        return CATALOG.stream()
                .map(p -> {
                    boolean priceConfigured = priceIds.getOrDefault(p.code(), "").isBlank() == false;
                    return new BillingDtos.PlanView(p.code(), p.name(), p.blurb(), p.audience(),
                            "monthly", stub || priceConfigured);
                })
                .toList();
    }

    @Transactional
    public BillingDtos.CheckoutView createCheckout(AuthUser user, String planCode) {
        Plan plan = CATALOG.stream().filter(p -> p.code().equals(planCode)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown plan"));
        String priceId = props.billing().plans().getOrDefault(plan.code(), "");
        if (!props.stripe().stubMode() && priceId.isBlank()) {
            throw ApiException.conflict("This plan is not configured for billing yet");
        }

        Subscription sub = new Subscription();
        sub.setUserId(user.id());
        sub.setPlanCode(plan.code());
        sub.setStatus("incomplete");
        sub = subscriptions.save(sub);

        StripeClient.CheckoutSession session =
                stripe.createSubscriptionCheckout(user.email(), priceId, sub.getId().toString());
        sub.setCheckoutSession(session.sessionId());
        subscriptions.save(sub);
        return new BillingDtos.CheckoutView(session.sessionId(), session.url());
    }

    /** Called from the verified webhook when a subscription checkout completes. */
    @Transactional
    public void activateBySession(String sessionId, String stripeSubscriptionId) {
        subscriptions.findByCheckoutSession(sessionId).ifPresent(sub -> {
            sub.setStatus("active");
            if (stripeSubscriptionId != null) {
                sub.setStripeSubscriptionId(stripeSubscriptionId);
            }
            subscriptions.save(sub);
            log.info("Subscription {} activated (plan {})", sub.getId(), sub.getPlanCode());
        });
    }

    @Transactional
    public void updateStatusBySubscriptionId(String stripeSubscriptionId, String status) {
        subscriptions.findByStripeSubscriptionId(stripeSubscriptionId).ifPresent(sub -> {
            sub.setStatus(status);
            subscriptions.save(sub);
        });
    }

    @Transactional(readOnly = true)
    public BillingDtos.SubscriptionView current(AuthUser user) {
        return subscriptions.findByUserIdOrderByCreatedAtDesc(user.id()).stream()
                .filter(s -> "active".equals(s.getStatus()))
                .findFirst()
                .map(s -> new BillingDtos.SubscriptionView(s.getPlanCode(), s.getStatus(), s.getCurrentPeriodEnd()))
                .orElse(null);
    }
}
