package com.fixbridge.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** Admin-editable Service Assessment & Dispatch fee catalog (pilot prices; editable, not constants). */
@Entity
@Table(name = "dispatch_fees")
@Getter
@Setter
@NoArgsConstructor
public class DispatchFee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_type", nullable = false)
    private String serviceType;

    @Column(name = "customer_price_cents", nullable = false)
    private long customerPriceCents;

    @Column(name = "contractor_visit_cents", nullable = false)
    private long contractorVisitCents;

    @Column(nullable = false)
    private boolean active = true;
}
