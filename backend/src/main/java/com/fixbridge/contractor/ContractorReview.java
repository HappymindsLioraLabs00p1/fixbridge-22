package com.fixbridge.contractor;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** A customer's rating of completed work. Ratings are derived from these rather than stored as a
 *  standalone number, so any average can be traced back to the jobs behind it. */
@Entity
@Table(name = "contractor_reviews")
@Getter @Setter @NoArgsConstructor
public class ContractorReview {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "contractor_id", nullable = false)
    private UUID contractorId;

    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(nullable = false)
    private int rating;

    @Column(columnDefinition = "text")
    private String comment;

    @CreationTimestamp @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
