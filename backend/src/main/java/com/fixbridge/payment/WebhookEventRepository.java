package com.fixbridge.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {
    boolean existsByProviderAndEventId(String provider, String eventId);
}
