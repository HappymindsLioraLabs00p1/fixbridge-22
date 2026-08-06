package com.fixbridge.job;

import com.fixbridge.common.enums.ProposalStatus;
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
 * A change order for newly discovered work (spec §12.3). {@code addedNetCents} is confidential
 * (contractor + admin only); {@code addedRetailCents} is what the customer approves and pays — the
 * customer never sees the net or the margin. Work must not continue until the customer approves.
 */
@Entity
@Table(name = "change_orders")
@Getter
@Setter
@NoArgsConstructor
public class ChangeOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(columnDefinition = "text", nullable = false)
    private String description;

    @Column(name = "added_net_cents", nullable = false)
    private long addedNetCents;

    @Column(name = "added_retail_cents", nullable = false)
    private long addedRetailCents;

    @Column(name = "added_days")
    private Integer addedDays;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "proposal_status", nullable = false)
    private ProposalStatus status = ProposalStatus.draft;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
