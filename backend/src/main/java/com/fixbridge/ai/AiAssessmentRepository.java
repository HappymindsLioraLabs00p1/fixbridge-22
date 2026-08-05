package com.fixbridge.ai;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AiAssessmentRepository extends JpaRepository<AiAssessmentEntity, UUID> {
    Optional<AiAssessmentEntity> findFirstByJobIdOrderByCreatedAtDesc(UUID jobId);
}
