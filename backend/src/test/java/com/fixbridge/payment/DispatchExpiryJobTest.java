package com.fixbridge.payment;

import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Money must not stay reserved against a job nobody took.
 *
 * <p>These assert both directions: a stale hold is released, and a hold still doing its job — a
 * fresh dispatch, an accepted contractor, an already-captured fee — is left alone. Releasing too
 * eagerly is its own failure: it hands back money for work somebody has committed to.
 */
class DispatchExpiryJobTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final VisitFeeHoldService holds = mock(VisitFeeHoldService.class);
    private final DispatchExpiryJob sweep = new DispatchExpiryJob(jobs, holds);

    DispatchExpiryJobTest() {
        ReflectionTestUtils.setField(sweep, "expiryHours", 48L);
        ReflectionTestUtils.setField(sweep, "enabled", true);
    }

    private Job job(JobStatus status, boolean held, boolean captured, Duration age) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setStatus(status);
        if (held) {
            j.setVisitFeeIntentId("pi_" + j.getId());
            j.setVisitFeeAuthorizedCents(9_900L);
        }
        if (captured) j.setVisitFeeCapturedAt(Instant.now());
        j.setUpdatedAt(Instant.now().minus(age));
        return j;
    }

    @Test
    void aHoldOnAJobNobodyTookIsReleased() {
        Job stale = job(JobStatus.awaiting_contractor, true, false, Duration.ofHours(72));
        when(jobs.findAll()).thenReturn(List.of(stale));

        sweep.releaseExpiredHolds();

        verify(holds).release(eq(stale.getId()), any());
    }

    @Test
    void aDispatchStillWithinItsWindowIsLeftAlone() {
        // Releasing here would cancel the payment for a job we are actively still placing.
        Job fresh = job(JobStatus.awaiting_contractor, true, false, Duration.ofHours(2));
        when(jobs.findAll()).thenReturn(List.of(fresh));

        sweep.releaseExpiredHolds();

        verify(holds, never()).release(any(), any());
    }

    @Test
    void aCapturedFeeIsNeverReleased() {
        // The contractor accepted and the money is already taken; handing it back would be a
        // silent refund for work somebody has committed to.
        Job taken = job(JobStatus.bid_received, true, true, Duration.ofHours(200));
        when(jobs.findAll()).thenReturn(List.of(taken));

        sweep.releaseExpiredHolds();

        verify(holds, never()).release(any(), any());
    }

    @Test
    void aJobPastDispatchIsNotSwept() {
        // Once a contractor is engaged the job is no longer "seeking", whatever its age.
        Job working = job(JobStatus.work_started, true, false, Duration.ofHours(200));
        when(jobs.findAll()).thenReturn(List.of(working));

        sweep.releaseExpiredHolds();

        verify(holds, never()).release(any(), any());
    }

    @Test
    void aJobWithNoHoldIsIgnored() {
        Job none = job(JobStatus.awaiting_contractor, false, false, Duration.ofHours(200));
        when(jobs.findAll()).thenReturn(List.of(none));

        sweep.releaseExpiredHolds();

        verify(holds, never()).release(any(), any());
    }

    @Test
    void theSweepCanBeTurnedOff() {
        ReflectionTestUtils.setField(sweep, "enabled", false);
        when(jobs.findAll()).thenReturn(List.of(
                job(JobStatus.awaiting_contractor, true, false, Duration.ofHours(200))));

        sweep.releaseExpiredHolds();

        verify(jobs, never()).findAll();
    }

    @Test
    void severalStaleJobsAreAllReleased() {
        // One awkward job must not stop the rest being cleaned up.
        Job a = job(JobStatus.awaiting_contractor, true, false, Duration.ofHours(72));
        Job b = job(JobStatus.contractor_invited, true, false, Duration.ofHours(96));
        Job c = job(JobStatus.paid_for_dispatch, true, false, Duration.ofHours(50));
        when(jobs.findAll()).thenReturn(List.of(a, b, c));

        sweep.releaseExpiredHolds();

        verify(holds).release(eq(a.getId()), any());
        verify(holds).release(eq(b.getId()), any());
        verify(holds).release(eq(c.getId()), any());
    }
}
