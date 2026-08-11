package com.fixbridge.ai;

import com.fixbridge.common.enums.AssessmentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Re-runs assessments that were stored as pending when the AI service was unavailable.
 *
 * <p>Graceful degradation writes a pending row rather than failing the job, which keeps the
 * customer moving. Without something to pick those rows up, though, a job whose assessment failed
 * stays unassessed forever — the {@code attempts}, {@code last_error} and {@code last_attempt_at}
 * columns exist precisely to support this sweep, and nothing was consuming them.
 *
 * <p>The sweep is deliberately small and slow. A batch limit plus exponential backoff means a
 * prolonged outage produces a trickle of retries rather than a storm the moment the service
 * returns, and a permanently failing assessment gives up instead of retrying forever.
 */
@Component
public class AiAssessmentRetryJob {

    private static final Logger log = LoggerFactory.getLogger(AiAssessmentRetryJob.class);

    /** Enough to clear a short outage in a few sweeps, small enough not to stampede the service. */
    private static final int BATCH = 10;

    /** After this many failures the assessment is left alone for a human to look at. */
    private static final int MAX_ATTEMPTS = 6;

    /** Backoff base: attempt n waits roughly 5 * 2^(n-1) minutes, capped. */
    private static final Duration BACKOFF_BASE = Duration.ofMinutes(5);
    private static final Duration BACKOFF_CAP = Duration.ofHours(6);

    private final AiService aiService;
    private final AiAssessmentRepository repository;

    @Value("${fixbridge.ai.retry-enabled:true}")
    private boolean enabled;

    public AiAssessmentRetryJob(AiService aiService, AiAssessmentRepository repository) {
        this.aiService = aiService;
        this.repository = repository;
    }

    /** Every five minutes. The backoff, not the schedule, decides when a given row is retried. */
    @Scheduled(fixedDelayString = "${fixbridge.ai.retry-interval-ms:300000}", initialDelay = 60_000)
    @Transactional
    public void run() {
        if (!enabled) return;

        List<AiAssessmentEntity> candidates = aiService.retryable(BATCH);
        int retried = 0, exhausted = 0, skipped = 0;

        for (AiAssessmentEntity entity : candidates) {
            if (entity.getStatus() == AssessmentStatus.completed) continue;

            if (entity.getAttempts() >= MAX_ATTEMPTS) {
                // Marked failed so it stops appearing in this sweep. The row and its last error
                // remain for an admin to inspect; the job keeps its conservative safety defaults.
                entity.setStatus(AssessmentStatus.failed);
                repository.save(entity);
                exhausted++;
                continue;
            }
            if (!isDue(entity)) {
                skipped++;
                continue;
            }

            String description = descriptionOf(entity);
            if (description == null) {
                // Nothing to re-assess from. Retrying would fail identically every time.
                entity.setStatus(AssessmentStatus.failed);
                entity.setLastError("No stored description to re-assess");
                repository.save(entity);
                exhausted++;
                continue;
            }

            entity.setAttempts(entity.getAttempts() + 1);
            entity.setLastAttemptAt(Instant.now());
            repository.save(entity);

            try {
                // assessAndStore writes a fresh row on success; this one stops being retryable
                // once that row supersedes it.
                aiService.assessAndStore(entity.getJobId(), description, List.of());
                entity.setStatus(AssessmentStatus.completed);
                repository.save(entity);
                retried++;
            } catch (Exception e) {
                // One failure must not abandon the rest of the batch.
                entity.setLastError(e.getMessage());
                repository.save(entity);
                log.warn("Retry {} for assessment {} failed: {}",
                        entity.getAttempts(), entity.getId(), e.getMessage());
            }
        }

        if (retried > 0 || exhausted > 0) {
            log.info("AI assessment retry sweep: {} retried, {} exhausted, {} not yet due",
                    retried, exhausted, skipped);
        }
    }

    /** Exponential backoff on attempts already made, so a failing row backs off rather than spins. */
    private boolean isDue(AiAssessmentEntity entity) {
        Instant last = entity.getLastAttemptAt();
        if (last == null) return true;
        long multiplier = 1L << Math.min(entity.getAttempts() - 1, 10);   // guard the shift
        Duration wait = BACKOFF_BASE.multipliedBy(multiplier);
        if (wait.compareTo(BACKOFF_CAP) > 0) wait = BACKOFF_CAP;
        return last.plus(wait).isBefore(Instant.now());
    }

    /** The description stashed by savePending, if it is still there. */
    private String descriptionOf(AiAssessmentEntity entity) {
        Map<String, Object> raw = entity.getRawJson();
        if (raw == null) return null;
        Object description = raw.get("description");
        if (description == null) return null;
        String text = description.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
