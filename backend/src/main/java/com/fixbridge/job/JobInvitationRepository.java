package com.fixbridge.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobInvitationRepository extends JpaRepository<JobInvitation, UUID> {
    List<JobInvitation> findByContractorId(UUID contractorId);
    List<JobInvitation> findByJobId(UUID jobId);
    Optional<JobInvitation> findByJobIdAndContractorId(UUID jobId, UUID contractorId);
}
