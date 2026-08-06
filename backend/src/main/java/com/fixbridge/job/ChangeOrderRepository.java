package com.fixbridge.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ChangeOrderRepository extends JpaRepository<ChangeOrder, UUID> {
    List<ChangeOrder> findByJobIdOrderByCreatedAtAsc(UUID jobId);
}
