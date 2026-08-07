package com.fixbridge.job;

import com.fixbridge.common.enums.JobMode;
import com.fixbridge.common.enums.JobStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter
@Setter
@NoArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "job_mode", nullable = false)
    private JobMode mode = JobMode.managed;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "job_status", nullable = false)
    private JobStatus status = JobStatus.draft;

    private String title;

    @Column(columnDefinition = "text")
    private String description;

    /** Labeled "preferred service time" until a contractor accepts. */
    @Column(name = "preferred_time")
    private String preferredTime;

    @Column(name = "assigned_contractor_id")
    private UUID assignedContractorId;

    /** Set by an admin to block the contractor payout (dispute, quality or compliance issue). */
    @Column(name = "payout_hold_reason", columnDefinition = "text")
    private String payoutHoldReason;

    // Lightweight referral / property-opportunity fields
    @Column(name = "partner_code")
    private String partnerCode;

    @Column(name = "referral_source")
    private String referralSource;

    @Column(name = "property_purpose")
    private String propertyPurpose;

    @Column(name = "transaction_stage")
    private String transactionStage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;
}
