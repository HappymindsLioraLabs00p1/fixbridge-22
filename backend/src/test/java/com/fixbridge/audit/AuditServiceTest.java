package com.fixbridge.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AuditServiceTest {

    @Test
    void record_persistsActorActionAndPayload() {
        AuditLogRepository repo = mock(AuditLogRepository.class);
        AuditService service = new AuditService(repo);

        UUID actor = UUID.randomUUID();
        UUID entity = UUID.randomUUID();
        service.record(actor, "payout.release", "transfer", entity, Map.of("amountCents", 53_000));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isEqualTo(actor);
        assertThat(saved.getAction()).isEqualTo("payout.release");
        assertThat(saved.getEntityType()).isEqualTo("transfer");
        assertThat(saved.getEntityId()).isEqualTo(entity);
        assertThat(saved.getAfter()).containsEntry("amountCents", 53_000);
    }
}
