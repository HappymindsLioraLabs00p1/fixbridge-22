package com.fixbridge.contractor;

import com.fixbridge.common.enums.ContractorStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContractorRepository extends JpaRepository<Contractor, UUID> {
    Optional<Contractor> findByOwnerUserId(UUID ownerUserId);
    List<Contractor> findByStatus(ContractorStatus status);
}
