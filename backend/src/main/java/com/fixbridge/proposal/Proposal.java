package com.fixbridge.proposal;

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
 * Customer-facing RETAIL proposal. {@code retailTotalCents} is what the customer pays; the contractor
 * never sees it (nor the margin). Only admin sees both retail and net.
 */
@Entity
@Table(name = "proposals")
@Getter
@Setter
@NoArgsConstructor
public class Proposal {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "proposal_status", nullable = false)
    private ProposalStatus status = ProposalStatus.draft;

    @Column(columnDefinition = "text")
    private String scope;

    @Column(name = "retail_total_cents", nullable = false)
    private long retailTotalCents;

    @Column(name = "deposit_cents")
    private long depositCents = 0;

    private String timeline;
    private String warranty;

    @Column(columnDefinition = "text")
    private String exclusions;

    @Column(columnDefinition = "text")
    private String terms;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
