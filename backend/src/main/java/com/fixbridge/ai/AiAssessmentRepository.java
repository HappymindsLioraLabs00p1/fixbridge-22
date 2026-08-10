package com.fixbridge.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiAssessmentRepository extends JpaRepository<AiAssessmentEntity, UUID> {
    Optional<AiAssessmentEntity> findFirstByJobIdOrderByCreatedAtDesc(UUID jobId);

    /** Assessments awaiting a retry, oldest attempt first. */
    @org.springframework.data.jpa.repository.Query(
            "select a from AiAssessmentEntity a where a.status <> com.fixbridge.common.enums.AssessmentStatus.completed "
          + "order by a.lastAttemptAt asc nulls first")
    java.util.List<AiAssessmentEntity> findRetryable(org.springframework.data.domain.Pageable page);
}
