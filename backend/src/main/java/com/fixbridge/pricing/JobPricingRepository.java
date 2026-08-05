package com.fixbridge.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobPricingRepository extends JpaRepository<JobPricing, UUID> {
    Optional<JobPricing> findByJobId(UUID jobId);
}
