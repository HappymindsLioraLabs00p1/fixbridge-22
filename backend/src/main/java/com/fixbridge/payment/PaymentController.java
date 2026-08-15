package com.fixbridge.payment;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.payment.dto.PaymentDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Customer pays the dispatch/assessment fee → returns a Checkout URL to redirect to. */
    @PostMapping("/jobs/{jobId}/dispatch-checkout")
    @PreAuthorize("hasRole('customer')")
    public PaymentDtos.CheckoutView dispatchCheckout(@PathVariable UUID jobId,
                                                     @Valid @RequestBody PaymentDtos.DispatchCheckoutRequest req) {
        return paymentService.createDispatchCheckout(SecurityUtil.currentUser(), jobId, req.serviceType());
    }

    /**
     * Settle a stub checkout, standing in for the Stripe webhook that cannot reach a local machine.
     * Both endpoints 404 when Stripe is live, so they exist only for local and staging testing — and
     * they are authenticated and ownership-checked like every other customer action.
     */
    @PostMapping("/checkouts/{sessionId}/stub-complete")
    @PreAuthorize("hasRole('customer')")
    public void completeStubCheckout(@PathVariable String sessionId) {
        paymentService.completeStubCheckout(SecurityUtil.currentUser(), sessionId);
    }

    @PostMapping("/checkouts/{sessionId}/stub-cancel")
    @PreAuthorize("hasRole('customer')")
    public void cancelStubCheckout(@PathVariable String sessionId) {
        paymentService.cancelStubCheckout(SecurityUtil.currentUser(), sessionId);
    }
}
