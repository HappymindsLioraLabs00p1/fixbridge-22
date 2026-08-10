package com.fixbridge.repair;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RepairPlanRepository extends JpaRepository<RepairPlanEntity, UUID> {
    Optional<RepairPlanEntity> findFirstByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
