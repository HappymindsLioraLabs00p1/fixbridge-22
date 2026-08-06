package com.fixbridge.billing;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.billing.dto.BillingDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/billing")
public class BillingController {

    private final SubscriptionService subscriptions;

    public BillingController(SubscriptionService subscriptions) {
        this.subscriptions = subscriptions;
    }

    @GetMapping("/plans")
    public List<BillingDtos.PlanView> plans() {
        return subscriptions.plans();
    }

    @PostMapping("/checkout")
    @PreAuthorize("isAuthenticated()")
    public BillingDtos.CheckoutView checkout(@Valid @RequestBody BillingDtos.SubscribeRequest req) {
        return subscriptions.createCheckout(SecurityUtil.currentUser(), req.planCode());
    }

    @GetMapping("/subscription")
    @PreAuthorize("isAuthenticated()")
    public BillingDtos.SubscriptionView current() {
        return subscriptions.current(SecurityUtil.currentUser());
    }
}
