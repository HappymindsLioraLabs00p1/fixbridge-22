package com.fixbridge.repair;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RepairStepRepository extends JpaRepository<RepairStep, UUID> {
    List<RepairStep> findByPlanIdOrderByStepNumberAsc(UUID planId);
}
