package com.fixbridge.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiAssessmentRepository extends JpaRepository<AiAssessmentEntity, UUID> {
    Optional<AiAssessmentEntity> findFirstByJobIdOrderByCreatedAtDesc(UUID jobId);

    /**
     * Assessments awaiting a retry, oldest attempt first.
     *
     * <p>Derived from the method name rather than written as JPQL. Naming the enum constant inline
     * made Hibernate cast the parameter to a type derived from the Java class name —
     * {@code assessmentstatus} — which does not exist; the column's type is
     * {@code assessment_status}. The query then failed at runtime with "type does not exist",
     * naming a type nobody had written anywhere, which reads like a schema problem rather than a
     * query one.
     */
    java.util.List<AiAssessmentEntity> findByStatusOrderByLastAttemptAtAsc(
            com.fixbridge.common.enums.AssessmentStatus status,
            org.springframework.data.domain.Pageable page);
}
