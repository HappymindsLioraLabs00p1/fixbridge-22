package com.fixbridge.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.storage.StorageService;
import com.fixbridge.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiServiceTest {

    private AiService serviceReturning(AssessmentResult fromClient, AiAssessmentRepository repo) {
        AiAssessmentClient client = mock(AiAssessmentClient.class);
        when(client.assess(any(), any())).thenReturn(fromClient);
        when(client.provider()).thenReturn("stub");
        when(client.model()).thenReturn("gpt-test");
        return new AiService(client, repo, new ObjectMapper(), mock(StorageService.class));
    }

    @Test
    void safetyRuleOverridesDiyOnEmergencyEvenIfModelAllowedIt() {
        AiAssessmentRepository repo = mock(AiAssessmentRepository.class);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        // Model wrongly says safe_diy=true on an emergency; the server must flip it to false.
        AssessmentResult modelSaid = TestFixtures.assessment("electrical", AiUrgency.emergency, 0.9, true, 1, 2);
        AiService service = serviceReturning(modelSaid, repo);

        AssessmentResult result = service.assessAndStore(UUID.randomUUID(), "sparks", List.of());

        assertThat(result.safeDiyAllowed()).isFalse();
        verify(repo).save(any());
    }

    @Test
    void safeCaseKeepsDiyAllowedAndPersists() {
        AiAssessmentRepository repo = mock(AiAssessmentRepository.class);
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        AssessmentResult modelSaid = TestFixtures.assessment("handyman", AiUrgency.low, 0.85, true, 1, 2);
        AiService service = serviceReturning(modelSaid, repo);

        AssessmentResult result = service.assessAndStore(UUID.randomUUID(), "loose hinge", List.of());

        assertThat(result.safeDiyAllowed()).isTrue();
        verify(repo).save(any());
    }
}
