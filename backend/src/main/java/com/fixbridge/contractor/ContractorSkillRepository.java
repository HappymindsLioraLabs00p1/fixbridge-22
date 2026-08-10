package com.fixbridge.contractor;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContractorSkillRepository extends JpaRepository<ContractorSkill, UUID> {
    List<ContractorSkill> findByTradeIgnoreCase(String trade);
    List<ContractorSkill> findByContractorId(UUID contractorId);
}
