package com.fixbridge.payment;

import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The hold on a homeowner's card.
 *
 * <p>These assert the rules that protect them: never charged more than they agreed to, never held
 * twice for one visit, and never left with money reserved for a job nobody took. The last one
 * matters most — an unreleased hold is invisible to us and very visible to the customer.
 */
class VisitFeeHoldServiceTest {

    private final StubStripeClient stripe = new StubStripeClient();
    private final JobRepository jobs = mock(JobRepository.class);
    private final VisitFeeHoldService service = new VisitFeeHoldService(stripe, jobs);

    private final UUID jobId = UUID.randomUUID();
    private Job job;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setId(jobId);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void acceptingTheQuoteReservesTheMoneyWithoutTakingIt() {
        var auth = service.hold(jobId, 9_900L);

        assertThat(auth.amountCents()).isEqualTo(9_900L);
        assertThat(job.getVisitFeeIntentId()).isNotBlank();
        // Reserved, not taken: nobody has agreed to come out yet.
        assertThat(job.getVisitFeeCapturedAt()).isNull();
        assertThat(stripe.heldAmount(auth.paymentIntentId())).isEqualTo(9_900L);
    }

    @Test
    void aContractorAcceptingTakesTheMoney() {
        var auth = service.hold(jobId, 9_900L);
        service.capture(jobId);

        assertThat(job.getVisitFeeCapturedAt()).isNotNull();
        assertThat(stripe.heldAmount(auth.paymentIntentId())).isNull();
    }

    @Test
    void nobodyAcceptingReturnsTheMoney() {
        // The item this exists for: a homeowner must not be left with funds reserved against a
        // job that never happened.
        var auth = service.hold(jobId, 9_900L);
        service.release(jobId, "No contractor accepted");

        assertThat(stripe.heldAmount(auth.paymentIntentId())).isNull();
        assertThat(job.getVisitFeeIntentId()).isNull();
        assertThat(job.getVisitFeeCapturedAt()).isNull();
    }

    @Test
    void theSameJobCannotBeHeldTwice() {
        service.hold(jobId, 9_900L);
        assertThatThrownBy(() -> service.hold(jobId, 9_900L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aCapturedFeeIsNeverCapturedAgain() {
        // Reported rather than thrown. This runs inside a contractor's acceptance, and an exception
        // crossing the transaction boundary would discard that acceptance at commit — taking the fee
        // twice and losing the bid are both worse than saying "nothing more to take".
        service.hold(jobId, 9_900L);
        assertThat(service.capture(jobId)).isTrue();
        var capturedAt = job.getVisitFeeCapturedAt();

        assertThat(service.capture(jobId)).isFalse();
        assertThat(job.getVisitFeeCapturedAt()).isEqualTo(capturedAt);
    }

    @Test
    void aCapturedFeeIsNotReleasedByALateCancellation() {
        // Releasing after capture would silently hand back money for work already committed to.
        service.hold(jobId, 9_900L);
        service.capture(jobId);
        var capturedAt = job.getVisitFeeCapturedAt();

        service.release(jobId, "late cancel");

        assertThat(job.getVisitFeeCapturedAt()).isEqualTo(capturedAt);
        assertThat(job.getVisitFeeIntentId()).isNotBlank();
    }

    @Test
    void capturingWithoutAHoldIsHarmless() {
        // A job can legitimately have no hold: one is placed only when the homeowner is quoted a
        // visit fee, and a waived dispatch fee reaches dispatch without ever authorising one.
        // Throwing here marked the caller's transaction rollback-only, so a contractor accepting
        // such a job got a 500 and their bid was discarded.
        assertThat(service.capture(jobId)).isFalse();

        assertThat(job.getVisitFeeCapturedAt()).isNull();
    }

    @Test
    void anAcceptanceSurvivesAStripeCaptureFailure() {
        // The hold is left in place for an admin to capture or release deliberately. Money left
        // uncaptured is visible and recoverable; a lost acceptance is neither.
        StripeClient failing = mock(StripeClient.class);
        doThrow(new RuntimeException("card declined"))
                .when(failing).captureAuthorization(any(), anyLong());
        when(failing.authorize(anyLong(), any(), any()))
                .thenReturn(new StripeClient.Authorization("pi_test", "secret", 9_900L));
        VisitFeeHoldService flaky = new VisitFeeHoldService(failing, jobs);
        flaky.hold(jobId, 9_900L);

        assertThat(flaky.capture(jobId)).isFalse();

        assertThat(job.getVisitFeeCapturedAt()).isNull();
        assertThat(job.getVisitFeeIntentId()).isNotBlank();
    }

    @Test
    void releasingWhenThereIsNoHoldIsHarmless() {
        // Cleanup runs on paths that may or may not have authorised; throwing here would abandon
        // the rest of the cleanup.
        service.release(jobId, "nothing to do");
        assertThat(job.getVisitFeeIntentId()).isNull();
    }

    @Test
    void aZeroOrNegativeFeeIsRefusedRatherThanAuthorised() {
        assertThatThrownBy(() -> service.hold(jobId, 0L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.hold(jobId, -100L)).isInstanceOf(ApiException.class);
    }

    @Test
    void captureUsesTheAmountTheHomeownerAgreedTo() {
        // Not recalculated at capture time: a rate change between acceptance and dispatch must not
        // produce a charge nobody consented to.
        service.hold(jobId, 9_900L);
        assertThat(job.getVisitFeeAuthorizedCents()).isEqualTo(9_900L);
        service.capture(jobId);   // the stub refuses a capture above the hold
        assertThat(job.getVisitFeeCapturedAt()).isNotNull();
    }

    @Test
    void aStripeFailureDuringReleaseStillClearsTheJob() {
        StripeClient failing = mock(StripeClient.class);
        when(failing.authorize(anyLong(), any(), any()))
                .thenReturn(new StripeClient.Authorization("pi_x", "cs_x", 9_900L));
        doThrow(new RuntimeException("stripe down"))
                .when(failing).releaseAuthorization(any(), any());

        var svc = new VisitFeeHoldService(failing, jobs);
        svc.hold(jobId, 9_900L);
        svc.release(jobId, "no contractor");

        // The local record is cleared even when Stripe refuses, so the job is not stuck believing
        // it still holds money it cannot account for.
        assertThat(job.getVisitFeeIntentId()).isNull();
    }
}
