package com.fixbridge.contractor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContractorReviewRepository extends JpaRepository<ContractorReview, UUID> {
    List<ContractorReview> findByContractorId(UUID contractorId);
}
