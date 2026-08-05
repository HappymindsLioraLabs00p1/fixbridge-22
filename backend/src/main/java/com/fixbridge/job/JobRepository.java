package com.fixbridge.job;

import com.fixbridge.common.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    List<Job> findByCustomerId(UUID customerId);
    List<Job> findByStatus(JobStatus status);
    List<Job> findByAssignedContractorId(UUID contractorId);
}
