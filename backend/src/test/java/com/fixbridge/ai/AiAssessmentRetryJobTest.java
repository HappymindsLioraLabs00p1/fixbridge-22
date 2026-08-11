package com.fixbridge.ai;

import com.fixbridge.common.enums.AssessmentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The retry sweep exists because a pending assessment was previously never picked up again. These
 * assert the properties that stop the fix becoming its own problem: that a failing row backs off
 * rather than spinning, that it eventually gives up, and that one bad row doesn't abandon the batch.
 */
class AiAssessmentRetryJobTest {

    private final AiService aiService = mock(AiService.class);
    private final AiAssessmentRepository repository = mock(AiAssessmentRepository.class);
    private final AiAssessmentRetryJob job = new AiAssessmentRetryJob(aiService, repository);

    AiAssessmentRetryJobTest() {
        ReflectionTestUtils.setField(job, "enabled", true);
    }

    private AiAssessmentEntity pending(int attempts, Instant lastAttempt) {
        AiAssessmentEntity e = new AiAssessmentEntity();
        e.setId(UUID.randomUUID());
        e.setJobId(UUID.randomUUID());
        e.setStatus(AssessmentStatus.pending);
        e.setAttempts(attempts);
        e.setLastAttemptAt(lastAttempt);
        e.setRawJson(Map.of("pending", true, "description", "kitchen sink leaking"));
        return e;
    }

    private static Instant agesAgo() {
        return Instant.now().minus(30, ChronoUnit.DAYS);
    }

    @Test
    void aDueAssessmentIsRetried() {
        when(aiService.retryable(anyInt())).thenReturn(List.of(pending(1, agesAgo())));

        job.run();

        verify(aiService).assessAndStore(any(), eq("kitchen sink leaking"), anyList());
    }

    @Test
    void aRecentlyAttemptedAssessmentIsLeftAlone() {
        when(aiService.retryable(anyInt())).thenReturn(List.of(pending(1, Instant.now())));

        job.run();

        verify(aiService, never()).assessAndStore(any(), any(), anyList());
    }

    @Test
    void backoffGrowsWithAttempts() {
        // Five minutes after one attempt is due; the same gap after five attempts is not.
        Instant tenMinutesAgo = Instant.now().minus(10, ChronoUnit.MINUTES);
        when(aiService.retryable(anyInt())).thenReturn(List.of(pending(1, tenMinutesAgo)));
        job.run();
        verify(aiService, times(1)).assessAndStore(any(), any(), anyList());

        reset(aiService);
        when(aiService.retryable(anyInt())).thenReturn(List.of(pending(5, tenMinutesAgo)));
        job.run();
        verify(aiService, never()).assessAndStore(any(), any(), anyList());
    }

    @Test
    void anAssessmentThatKeepsFailingEventuallyGivesUp() {
        AiAssessmentEntity exhausted = pending(6, agesAgo());
        when(aiService.retryable(anyInt())).thenReturn(List.of(exhausted));

        job.run();

        assertThat(exhausted.getStatus()).isEqualTo(AssessmentStatus.failed);
        verify(aiService, never()).assessAndStore(any(), any(), anyList());
    }

    @Test
    void anAssessmentWithNoStoredDescriptionIsNotRetriedForever() {
        AiAssessmentEntity noDescription = pending(1, agesAgo());
        noDescription.setRawJson(Map.of("pending", true));
        when(aiService.retryable(anyInt())).thenReturn(List.of(noDescription));

        job.run();

        assertThat(noDescription.getStatus()).isEqualTo(AssessmentStatus.failed);
        verify(aiService, never()).assessAndStore(any(), any(), anyList());
    }

    @Test
    void oneFailingRetryDoesNotAbandonTheRestOfTheBatch() {
        AiAssessmentEntity first = pending(1, agesAgo());
        AiAssessmentEntity second = pending(1, agesAgo());
        when(aiService.retryable(anyInt())).thenReturn(List.of(first, second));
        when(aiService.assessAndStore(eq(first.getJobId()), any(), anyList()))
                .thenThrow(new RuntimeException("service still down"));

        job.run();

        verify(aiService).assessAndStore(eq(second.getJobId()), any(), anyList());
        assertThat(first.getStatus()).isEqualTo(AssessmentStatus.pending);   // will be tried again
        assertThat(first.getLastError()).contains("service still down");
    }

    @Test
    void aSuccessfulRetryMarksTheRowCompleted() {
        AiAssessmentEntity entity = pending(1, agesAgo());
        when(aiService.retryable(anyInt())).thenReturn(List.of(entity));

        job.run();

        assertThat(entity.getStatus()).isEqualTo(AssessmentStatus.completed);
        assertThat(entity.getAttempts()).isEqualTo(2);
    }

    @Test
    void theSweepDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(job, "enabled", false);
        when(aiService.retryable(anyInt())).thenReturn(List.of(pending(1, agesAgo())));

        job.run();

        verify(aiService, never()).retryable(anyInt());
    }
}
