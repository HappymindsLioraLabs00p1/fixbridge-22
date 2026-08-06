package com.fixbridge.audit;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Writes audit records for sensitive actions. Runs in the caller's transaction so the audit row commits
 * (or rolls back) atomically with the action it records — an action never "happens" without its log.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    public void record(UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> after) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setAfter(after);
        repository.save(log);
    }
}
