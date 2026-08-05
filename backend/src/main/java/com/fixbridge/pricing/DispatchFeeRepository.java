package com.fixbridge.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DispatchFeeRepository extends JpaRepository<DispatchFee, UUID> {
    Optional<DispatchFee> findByServiceTypeAndActiveTrue(String serviceType);
    List<DispatchFee> findByActiveTrue();
}
