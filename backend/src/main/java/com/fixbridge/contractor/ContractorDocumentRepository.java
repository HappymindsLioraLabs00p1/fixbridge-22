package com.fixbridge.contractor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ContractorDocumentRepository extends JpaRepository<ContractorDocument, UUID> {

    List<ContractorDocument> findByContractorIdOrderByCreatedAtDesc(UUID contractorId);

    /** Documents expiring on a given date — drives the 30/14/7-day reminders. */
    @Query("select d from ContractorDocument d where d.expiresOn = :date and d.status = com.fixbridge.common.enums.DocumentStatus.valid")
    List<ContractorDocument> findValidExpiringOn(LocalDate date);
}
