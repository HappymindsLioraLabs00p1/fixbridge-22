package com.fixbridge.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {
    List<Dispute> findByPaymentId(UUID paymentId);
    Optional<Dispute> findByStripeDisputeId(String stripeDisputeId);
}
