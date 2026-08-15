package com.fixbridge.job;

import com.fixbridge.common.enums.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {
    List<Job> findByCustomerId(UUID customerId);
    List<Job> findByStatus(JobStatus status);

    /** Jobs in any of several states — the admin queue spans more than one. */
    List<Job> findByStatusIn(java.util.Collection<JobStatus> statuses);
    List<Job> findByAssignedContractorId(UUID contractorId);
}
