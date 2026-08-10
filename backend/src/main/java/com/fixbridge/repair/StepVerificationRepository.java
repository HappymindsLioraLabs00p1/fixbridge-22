package com.fixbridge.repair;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StepVerificationRepository extends JpaRepository<StepVerification, UUID> {
    List<StepVerification> findByStepIdOrderByCreatedAtDesc(UUID stepId);
}
