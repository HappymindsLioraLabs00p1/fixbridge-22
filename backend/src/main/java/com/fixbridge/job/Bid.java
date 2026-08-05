package com.fixbridge.job;

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
import java.time.LocalDate;
import java.util.UUID;

/**
 * CONFIDENTIAL contractor net bid. The {@code netTotalCents} and cost breakdown are visible only to
 * the submitting contractor and admin — never to the customer.
 */
@Entity
@Table(name = "bids")
@Getter
@Setter
@NoArgsConstructor
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "contractor_id", nullable = false)
    private UUID contractorId;

    @Column(name = "labor_cents")
    private long laborCents = 0;

    @Column(name = "materials_cents")
    private long materialsCents = 0;

    @Column(name = "equipment_cents")
    private long equipmentCents = 0;

    @Column(name = "travel_cents")
    private long travelCents = 0;

    @Column(name = "permit_cents")
    private long permitCents = 0;

    @Column(name = "disposal_cents")
    private long disposalCents = 0;

    @Column(name = "net_total_cents", nullable = false)
    private long netTotalCents;

    @Column(name = "earliest_start")
    private LocalDate earliestStart;

    @Column(name = "duration_days")
    private Integer durationDays;

    private String warranty;

    @Column(columnDefinition = "text")
    private String exclusions;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
