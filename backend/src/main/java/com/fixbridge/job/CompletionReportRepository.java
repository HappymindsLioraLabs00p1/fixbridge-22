package com.fixbridge.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompletionReportRepository extends JpaRepository<CompletionReport, UUID> {
    Optional<CompletionReport> findFirstByJobIdOrderByCreatedAtDesc(UUID jobId);
}
