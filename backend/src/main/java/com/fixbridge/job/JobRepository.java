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

    /**
     * Read a job for a status change, holding the row until the transaction commits.
     *
     * <p>Two requests that both read {@code scheduled} would both write {@code work_started} and both
     * record a history entry, so the job's timeline would show it starting twice. Serialising the
     * read means the second caller sees the first caller's result and can treat its own request as
     * already done.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from Job j where j.id = :id")
    java.util.Optional<Job> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);
    List<Job> findByAssignedContractorId(UUID contractorId);
}
