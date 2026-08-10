package com.fixbridge.repair;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<RepairConversation, UUID> {
    List<RepairConversation> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);
}
