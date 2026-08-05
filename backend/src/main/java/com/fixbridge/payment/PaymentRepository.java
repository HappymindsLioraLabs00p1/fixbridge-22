package com.fixbridge.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
    List<Payment> findByJobId(UUID jobId);
    Optional<Payment> findByStripeCheckoutSession(String sessionId);
    Optional<Payment> findByStripePaymentIntent(String paymentIntentId);
}
