package com.fixbridge.payment;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.pricing.DispatchFeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * When a contractor becomes owed money — and the many moments that look like it but are not.
 *
 * <p>Every state below is one where somebody has done something significant: the customer approved a
 * price, the customer paid, the job was booked, the contractor turned up and started, extra work was
 * reported. None of them is the contractor having finished the job to the customer's satisfaction,
 * which is the only thing that earns a payout.
 *
 * <p>These are deliberately exhaustive rather than representative. A payout released one state too
 * early sends real money to somebody who has not done the work, and it is not recoverable by fixing
 * the code afterwards.
 */
class PayoutEligibilityTest {

    private final JobService jobService = mock(JobService.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final StripeClient stripe = mock(StripeClient.class);
    private final com.fixbridge.job.JobRepository jobs = mock(com.fixbridge.job.JobRepository.class);

    private final AuthUser admin =
            new AuthUser(UUID.randomUUID(), "admin@example.test", List.of(UserRole.admin));

    private PaymentService service;
    private Job job;

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        service = new PaymentService(mock(PaymentRepository.class), mock(DispatchFeeRepository.class),
                mock(com.fixbridge.proposal.ProposalRepository.class),
                mock(com.fixbridge.job.BidRepository.class), contractors, transfers, stripe, jobService,
                mock(com.fixbridge.notification.NotificationService.class), mock(RefundRepository.class),
                mock(DisputeRepository.class), mock(com.fixbridge.audit.AuditService.class), props,
                mock(com.fixbridge.job.AutoDispatchService.class), jobs);

        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        c.setStripeAccountId("acct_test");
        c.setConnectOnboarded(true);
        c.setPayoutsEnabled(true);
        when(contractors.findById(c.getId())).thenReturn(Optional.of(c));

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setAssignedContractorId(c.getId());
        when(jobService.requireJob(job.getId())).thenReturn(job);
        // The release reads the job under a lock. Without this the job would simply be "not found"
        // and every assertion below would pass for the wrong reason, testing nothing.
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(transfers.findByJobId(job.getId())).thenReturn(List.of());
    }

    private void assertNotPayable(JobStatus status, String because) {
        job.setStatus(status);

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .as(because)
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
        verify(transfers, never()).save(any());
    }

    // ---- Tests 13–15: none of these earns a payout ----

    @Test
    void approvingAProposalDoesNotMakeThePayoutEligible() {
        assertNotPayable(JobStatus.approved, "the customer agreeing a price is not work being done");
    }

    @Test
    void payingDoesNotMakeThePayoutEligible() {
        // The customer's money reaching FixBridge is not the contractor earning it.
        assertNotPayable(JobStatus.scheduled, "a paid, booked job has not been attended yet");
    }

    @Test
    void startingWorkDoesNotMakeThePayoutEligible() {
        assertNotPayable(JobStatus.work_started, "turning up is not finishing");
    }

    @Test
    void reportingExtraWorkDoesNotMakeThePayoutEligible() {
        assertNotPayable(JobStatus.change_order_pending, "extra work is not even approved yet");
    }

    @Test
    void winningTheBidDoesNotMakeThePayoutEligible() {
        assertNotPayable(JobStatus.bid_received, "a bid is a price, not a completed job");
    }

    @Test
    void beingDispatchedDoesNotMakeThePayoutEligible() {
        assertNotPayable(JobStatus.awaiting_contractor, "nobody has even accepted it");
    }

    @Test
    void aProposalSentDoesNotMakeThePayoutEligible() {
        assertNotPayable(JobStatus.proposal_sent, "the customer has not agreed the price");
    }

    // ---- Test 16: completion must be signed off, not merely claimed ----

    @Test
    void completedWorkAwaitingSignOffIsStillNotPayable() {
        // The contractor says it is done. Until the customer or an admin agrees, it is a claim.
        job.setStatus(JobStatus.customer_review_pending);
        when(jobService.isCompletionApproved(job.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void aSignedOffJobUnderAPayoutHoldIsNotPayable() {
        // A hold is an admin's deliberate block — a dispute, or a complaint being looked into.
        job.setStatus(JobStatus.admin_review_pending);
        job.setPayoutHoldReason("Customer reported a leak the next day");
        when(jobService.isCompletionApproved(job.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void aSignedOffJobWithNoAssignedContractorIsNotPayable() {
        job.setStatus(JobStatus.admin_review_pending);
        job.setAssignedContractorId(null);
        when(jobService.isCompletionApproved(job.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    // ---- Test 17: nothing in this increment releases a payout ----

    @Test
    void noStateReachedByTheWorkLifecycleSoFarReleasesAPayout() {
        // Every state the application can currently reach without an admin explicitly releasing a
        // payout on signed-off work. Sweeping them together so a new transition added later cannot
        // quietly become payable without this failing.
        for (JobStatus status : List.of(
                JobStatus.awaiting_service_payment, JobStatus.paid_for_dispatch,
                JobStatus.awaiting_contractor, JobStatus.contractor_invited,
                JobStatus.bid_received, JobStatus.proposal_sent, JobStatus.approved,
                JobStatus.scheduled, JobStatus.work_started, JobStatus.change_order_pending)) {
            job.setStatus(status);
            assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                    .as("payout must not be possible from " + status)
                    .isInstanceOf(ApiException.class);
        }
        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
        verify(transfers, never()).save(any());
    }
}
