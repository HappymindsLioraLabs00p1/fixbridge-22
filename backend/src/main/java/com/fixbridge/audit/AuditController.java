package com.fixbridge.audit;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Admin-only view of the audit trail. */
@RestController
@RequestMapping("/api/admin/audit-logs")
@PreAuthorize("hasRole('admin')")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    public record AuditView(UUID id, UUID actorId, String action, String entityType, UUID entityId,
                            Map<String, Object> after, Instant createdAt) {}

    @GetMapping
    public List<AuditView> recent() {
        return repository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(a -> new AuditView(a.getId(), a.getActorId(), a.getAction(), a.getEntityType(),
                        a.getEntityId(), a.getAfter(), a.getCreatedAt()))
                .toList();
    }
}
