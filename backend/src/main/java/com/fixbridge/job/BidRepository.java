package com.fixbridge.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidRepository extends JpaRepository<Bid, UUID> {
    List<Bid> findByJobId(UUID jobId);

    /** A contractor answers an invitation once, so this is at most one row. */
    Optional<Bid> findByJobIdAndContractorId(UUID jobId, UUID contractorId);
}
