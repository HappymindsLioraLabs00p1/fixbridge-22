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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Contractor's proof of completion (FR-JOB-7): arrival/completion times, before/after photos,
 * work summary, materials, invoice and warranty. The customer or an admin then confirms it
 * (FR-JOB-8) — payout is blocked until that happens.
 */
@Entity
@Table(name = "completion_reports")
@Getter
@Setter
@NoArgsConstructor
public class CompletionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "materials_used", columnDefinition = "text")
    private String materialsUsed;

    /** Private storage object keys — served to viewers as short-lived signed URLs. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "before_keys", columnDefinition = "text[]")
    private String[] beforeKeys;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "after_keys", columnDefinition = "text[]")
    private String[] afterKeys;

    @Column(name = "invoice_url", columnDefinition = "text")
    private String invoiceUrl;

    @Column(name = "warranty_text", columnDefinition = "text")
    private String warrantyText;

    /** Set when the customer (or admin) confirms the work — the gate before payout. */
    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public boolean isApproved() {
        return approvedBy != null;
    }
}
