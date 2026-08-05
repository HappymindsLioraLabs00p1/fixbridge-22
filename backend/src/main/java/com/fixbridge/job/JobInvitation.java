package com.fixbridge.job;

import com.fixbridge.common.enums.InvitationStatus;
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

@Entity
@Table(name = "job_invitations")
@Getter
@Setter
@NoArgsConstructor
public class JobInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "contractor_id", nullable = false)
    private UUID contractorId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "invitation_status", nullable = false)
    private InvitationStatus status = InvitationStatus.invited;

    /** Expected net payout shown to the contractor (never the customer retail price). */
    @Column(name = "expected_net_cents")
    private Long expectedNetCents;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public JobInvitation(UUID jobId, UUID contractorId, Long expectedNetCents) {
        this.jobId = jobId;
        this.contractorId = contractorId;
        this.expectedNetCents = expectedNetCents;
    }
}
