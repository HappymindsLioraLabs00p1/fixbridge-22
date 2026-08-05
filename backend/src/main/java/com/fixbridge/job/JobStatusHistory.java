package com.fixbridge.job;

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
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_status_history")
@Getter
@Setter
@NoArgsConstructor
public class JobStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "from_status", columnDefinition = "job_status")
    private JobStatus fromStatus;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "to_status", columnDefinition = "job_status", nullable = false)
    private JobStatus toStatus;

    @Column(name = "actor_id")
    private UUID actorId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    public JobStatusHistory(UUID jobId, JobStatus fromStatus, JobStatus toStatus, UUID actorId) {
        this.jobId = jobId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
    }
}
