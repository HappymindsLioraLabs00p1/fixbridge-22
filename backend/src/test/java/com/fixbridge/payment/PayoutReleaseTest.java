package com.fixbridge.payment;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.enums.TransferStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Bid;
import com.fixbridge.job.ChangeOrder;
import com.fixbridge.job.ChangeOrderRepository;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.notification.NotificationService;
import com.fixbridge.pricing.DispatchFeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Sending the contractor their money.
 *
 * <p>The one operation in the platform that cannot be undone by fixing the code afterwards. A
 * duplicate transfer is not a stray row: it is a second real payment, already in somebody else's
 * account, and the platform is simply out the money.
 *
 * <p>So the interesting cases here are not the happy path. They are the retry, the two admins
 * clicking at once, and the provider failing halfway — each of which must end with exactly one
 * payment or none, never two.
 */
class PayoutReleaseTest {

    private final JobService jobService = mock(JobService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final BidRepository bids = mock(BidRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final StripeClient stripe = mock(StripeClient.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ChangeOrderRepository changeOrders = mock(ChangeOrderRepository.class);

    /** Stands in for the change_orders table for this job. */
    private final List<ChangeOrder> orders = new java.util.ArrayList<>();

    private final UUID adminId = UUID.randomUUID();
    private final AuthUser admin = new AuthUser(adminId, "admin@example.test", List.of(UserRole.admin));

    private PaymentService service;
    private Job job;
    private Contractor contractor;

    /** Stands in for the transfers table: what is saved is what a later lookup finds. */
    private final List<Transfer> stored = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        service = new PaymentService(mock(PaymentRepository.class), mock(DispatchFeeRepository.class),
                mock(com.fixbridge.proposal.ProposalRepository.class), bids, contractors, transfers,
                stripe, jobService, notifications, mock(RefundRepository.class),
                mock(DisputeRepository.class), mock(com.fixbridge.audit.AuditService.class), props,
                mock(com.fixbridge.job.AutoDispatchService.class), jobs, changeOrders);

        contractor = new Contractor();
        contractor.setId(UUID.randomUUID());
        contractor.setStatus(com.fixbridge.common.enums.ContractorStatus.approved);
        contractor.setStripeAccountId("acct_test_payout");
        contractor.setConnectOnboarded(true);
        contractor.setPayoutsEnabled(true);
        when(contractors.findById(contractor.getId())).thenReturn(Optional.of(contractor));

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setStatus(JobStatus.admin_review_pending);
        job.setAssignedContractorId(contractor.getId());
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(jobService.requireJob(job.getId())).thenReturn(job);
        when(jobService.isCompletionApproved(job.getId())).thenReturn(true);

        Bid bid = new Bid();
        bid.setId(UUID.randomUUID());
        bid.setJobId(job.getId());
        bid.setContractorId(contractor.getId());
        bid.setNetTotalCents(21_000L);
        bid.setCreatedAt(Instant.now());
        when(bids.findByJobId(job.getId())).thenReturn(List.of(bid));

        when(stripe.createTransfer(any(), anyLong(), any(), any())).thenReturn("tr_test_1");
        when(transfers.save(any())).thenAnswer(i -> {
            Transfer t = i.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            stored.add(t);
            return t;
        });
        when(transfers.findByJobId(job.getId())).thenAnswer(i -> List.copyOf(stored));
        when(changeOrders.findByJobIdOrderByCreatedAtAsc(job.getId()))
                .thenAnswer(i -> List.copyOf(orders));
    }

    private void changeOrder(ProposalStatus status, long addedNetCents) {
        ChangeOrder co = new ChangeOrder();
        co.setId(UUID.randomUUID());
        co.setJobId(job.getId());
        co.setAddedNetCents(addedNetCents);
        co.setStatus(status);
        orders.add(co);
    }

    // ---- Test 1, 9, 11: the eligible release ----

    @Test
    void anEligibleCompletedJobIsPaidOut() {
        var view = service.releasePayout(admin, job.getId());

        assertThat(view.amountCents()).isEqualTo(21_000L);
        assertThat(view.status()).isEqualTo("paid");
        verify(stripe).createTransfer(eq("acct_test_payout"), eq(21_000L), eq("USD"), eq(job.getId().toString()));
        verify(jobService).transition(eq(job), eq(JobStatus.paid_out), eq(adminId));
        verify(notifications).payoutReleased(contractor.getId(), job.getId(), 21_000L);
    }

    @Test
    void theContractorIsPaidTheirNetBidNotTheCustomerPrice() {
        // The customer paid retail; the contractor is owed their own figure. Paying out the retail
        // would hand over the platform's margin as well.
        service.releasePayout(admin, job.getId());

        verify(stripe).createTransfer(any(), eq(21_000L), any(), any());
        assertThat(stored).singleElement()
                .satisfies(t -> assertThat(t.getAmountCents()).isEqualTo(21_000L));
    }

    @Test
    void theTransferRecordsWhoReleasedItAndAgainstWhichJob() {
        service.releasePayout(admin, job.getId());

        assertThat(stored).singleElement().satisfies(t -> {
            assertThat(t.getJobId()).isEqualTo(job.getId());
            assertThat(t.getContractorId()).isEqualTo(contractor.getId());
            assertThat(t.getReleasedBy()).isEqualTo(adminId);
            assertThat(t.getStatus()).isEqualTo(TransferStatus.paid);
            assertThat(t.getStripeTransferId()).isEqualTo("tr_test_1");
        });
    }

    // ---- Approved extra work is part of what the contractor is owed ----

    @Test
    void approvedExtraWorkIsPaidOnTopOfTheBid() {
        // The bid is what was quoted before anything was opened up. Work discovered afterwards and
        // approved by the customer is work the contractor actually did — paying the bid alone left
        // them short by exactly that amount while the platform kept the money.
        changeOrder(ProposalStatus.approved, 8_000L);

        var view = service.releasePayout(admin, job.getId());

        assertThat(view.amountCents()).isEqualTo(29_000L);   // 21,000 bid + 8,000 approved extra
        verify(stripe).createTransfer(any(), eq(29_000L), any(), any());
    }

    @Test
    void severalApprovedChangeOrdersAllCount() {
        changeOrder(ProposalStatus.approved, 8_000L);
        changeOrder(ProposalStatus.approved, 3_500L);

        assertThat(service.releasePayout(admin, job.getId()).amountCents()).isEqualTo(32_500L);
    }

    @Test
    void declinedExtraWorkIsNotPaid() {
        // The customer said no, so it was never authorised. Paying for it would charge the platform
        // for work the customer refused.
        changeOrder(ProposalStatus.declined, 8_000L);

        assertThat(service.releasePayout(admin, job.getId()).amountCents()).isEqualTo(21_000L);
    }

    @Test
    void unapprovedExtraWorkIsNotPaid() {
        // draft is not yet priced and sent is not yet agreed. Completion sign-off refuses to proceed
        // while either is outstanding, so neither should ever reach a payout — asserted anyway,
        // because the cost of being wrong here is paying for work nobody authorised.
        changeOrder(ProposalStatus.draft, 8_000L);
        changeOrder(ProposalStatus.sent, 4_000L);

        assertThat(service.releasePayout(admin, job.getId()).amountCents()).isEqualTo(21_000L);
    }

    @Test
    void aJobWithNoExtraWorkStillPaysTheBid() {
        // The ordinary case must not move.
        assertThat(service.releasePayout(admin, job.getId()).amountCents()).isEqualTo(21_000L);
    }

    @Test
    void theContractorIsToldTheAmountTheyAreActuallyPaid() {
        // The notification and the audit record used the bid alone. A contractor told one figure and
        // paid another is how a dispute starts, and the audit trail would have backed the wrong one.
        changeOrder(ProposalStatus.approved, 8_000L);

        service.releasePayout(admin, job.getId());

        verify(notifications).payoutReleased(contractor.getId(), job.getId(), 29_000L);
        assertThat(stored).singleElement()
                .satisfies(t -> assertThat(t.getAmountCents()).isEqualTo(29_000L));
    }

    // ---- Tests 5, 6, 10: never twice ----

    @Test
    void aRetriedReleaseReturnsTheExistingPayoutAndSendsNothing() {
        var first = service.releasePayout(admin, job.getId());
        clearInvocations(stripe, jobService, notifications);

        var second = service.releasePayout(admin, job.getId());

        assertThat(second.transferId()).isEqualTo(first.transferId());
        assertThat(second.status()).isEqualTo("paid");
        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
        assertThat(stored).hasSize(1);
    }

    @Test
    void anAlreadyPaidJobIsNotPaidAgainEvenFromAnotherState() {
        // The guard is the existing transfer, not the job status — a job moved on by some other
        // path must still not be payable a second time.
        service.releasePayout(admin, job.getId());
        job.setStatus(JobStatus.closed);
        clearInvocations(stripe);

        service.releasePayout(admin, job.getId());

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
        assertThat(stored).hasSize(1);
    }

    @Test
    void theJobIsLockedForTheWholeReleaseSoTwoAdminsCannotBothPay() {
        // Both requests would otherwise pass every check before either wrote a transfer. The lock is
        // what makes the second one see the first one's payout.
        service.releasePayout(admin, job.getId());

        verify(jobs).findByIdForUpdate(job.getId());
    }

    // ---- Tests 7 & 8: the provider fails ----

    @Test
    void aProviderFailureDoesNotRecordAPayout() {
        when(stripe.createTransfer(any(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("Stripe unavailable"));

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(RuntimeException.class);

        // Nothing is written, so nothing claims the contractor was paid. The transaction rolls back
        // the payout_pending transition with it, leaving the job exactly where an admin left it.
        assertThat(stored).isEmpty();
        verify(jobService, never()).transition(any(), eq(JobStatus.paid_out), any());
        verify(notifications, never()).payoutReleased(any(), any(), anyLong());
    }

    @Test
    void aReleaseCanBeRetriedAfterTheProviderRecovers() {
        when(stripe.createTransfer(any(), anyLong(), any(), any()))
                .thenThrow(new RuntimeException("Stripe unavailable"))
                .thenReturn("tr_test_recovered");

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(RuntimeException.class);
        var view = service.releasePayout(admin, job.getId());

        assertThat(view.status()).isEqualTo("paid");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getStripeTransferId()).isEqualTo("tr_test_recovered");
    }

    // ---- Test 2: ineligible jobs ----

    @Test
    void aJobWhoseCompletionIsNotSignedOffIsNotPaid() {
        when(jobService.isCompletionApproved(job.getId())).thenReturn(false);

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
        assertThat(stored).isEmpty();
    }

    @Test
    void aJobUnderAPayoutHoldIsNotPaid() {
        job.setPayoutHoldReason("Customer reported a leak the next day");

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void aContractorWhoseOnboardingIsIncompleteIsNotPaid() {
        // Money sent to an account that cannot receive it is money stuck in limbo.
        contractor.setPayoutsEnabled(false);

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void aJobWithNoBidHasNoAmountToPayAndIsRefused() {
        when(bids.findByJobId(job.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void anUnknownJobIsRefused() {
        UUID missing = UUID.randomUUID();
        when(jobs.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.releasePayout(admin, missing))
                .isInstanceOf(ApiException.class);
    }
}
