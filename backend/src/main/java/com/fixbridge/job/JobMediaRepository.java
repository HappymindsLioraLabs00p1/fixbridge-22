package com.fixbridge.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobMediaRepository extends JpaRepository<JobMedia, UUID> {
    List<JobMedia> findByJobIdOrderByCreatedAtAsc(UUID jobId);
}
