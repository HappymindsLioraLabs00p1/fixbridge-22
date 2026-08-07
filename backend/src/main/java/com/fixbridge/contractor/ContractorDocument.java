package com.fixbridge.contractor;

import com.fixbridge.common.enums.DocumentStatus;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * A compliance document for a contractor — licence, insurance, workers' comp or W-9 (FR-CON-1).
 * A contractor may not be dispatched while a REQUIRED document is missing, unverified or expired
 * (FR-CON-3), which is a liability control, not a nicety.
 */
@Entity
@Table(name = "contractor_documents")
@Getter
@Setter
@NoArgsConstructor
public class ContractorDocument {

    /** Document kinds we track. LICENSE and INSURANCE are required to work. */
    public static final String LICENSE = "license";
    public static final String INSURANCE = "insurance";
    public static final String WORKERS_COMP = "workers_comp";
    public static final String W9 = "w9";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "contractor_id", nullable = false)
    private UUID contractorId;

    @Column(nullable = false)
    private String kind;

    private String jurisdiction;

    private String number;

    /** Private storage object key — viewed through a short-lived signed URL. */
    @Column(name = "storage_key")
    private String storageKey;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "document_status", nullable = false)
    private DocumentStatus status = DocumentStatus.pending;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    /** Expired documents never count as valid, whatever the stored status says. */
    public boolean isCurrentlyValid() {
        if (status != DocumentStatus.valid) return false;
        return expiresOn == null || !expiresOn.isBefore(LocalDate.now());
    }

    public boolean isExpired() {
        return expiresOn != null && expiresOn.isBefore(LocalDate.now());
    }

    /** Days until expiry, or null when the document never expires. */
    public Long daysUntilExpiry() {
        if (expiresOn == null) return null;
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), expiresOn);
    }
}
