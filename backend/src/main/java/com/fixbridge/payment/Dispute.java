package com.fixbridge.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A chargeback raised by the customer's bank, recorded from Stripe's charge.dispute.created webhook. */
@Entity
@Table(name = "disputes")
@Getter
@Setter
@NoArgsConstructor
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "stripe_dispute_id")
    private String stripeDisputeId;

    private String status;

    @Column(name = "amount_cents")
    private Long amountCents;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
