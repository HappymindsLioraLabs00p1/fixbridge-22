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
}
