package com.fixbridge.payment;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.PaymentStatus;
import com.fixbridge.common.enums.PaymentType;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.pricing.DispatchFeeRepository;
import com.fixbridge.proposal.Proposal;
import com.fixbridge.proposal.ProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * Paying for the repair itself.
 *
 * <p>The dispatch fee had coverage; this half — the money that actually pays for the work — had none,
 * and it decides whether a customer who has paid gets their job scheduled.
 *
 * <p>Two properties matter more than the happy path. A job must reach scheduled only on a payment
 * the server has confirmed, never on a client saying so; and paying must move nothing towards the
 * contractor, because a payout is earned by completing work, not by the customer's money arriving.
 * Those are separate stages and a test that lets them merge would hide the worst possible bug.
 */
class RepairPaymentTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ProposalRepository proposals = mock(ProposalRepository.class);
    private final StripeClient stripe = mock(StripeClient.class);
    private final JobService jobService = mock(JobService.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final com.fixbridge.job.AutoDispatchService autoDispatch =
            mock(com.fixbridge.job.AutoDispatchService.class);

    private final UUID customerId = UUID.randomUUID();
    private final AuthUser customer = new AuthUser(customerId, "c@example.test", List.of(UserRole.customer));

    private PaymentService service;
    private Job job;

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        // save() assigns the identifier, as JPA does — the checkout reference is built from it.
        when(payments.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        service = new PaymentService(payments, mock(DispatchFeeRepository.class), proposals,
                mock(com.fixbridge.job.BidRepository.class), contractors, transfers, stripe, jobService,
                mock(com.fixbridge.notification.NotificationService.class), mock(RefundRepository.class),
                mock(DisputeRepository.class), mock(com.fixbridge.audit.AuditService.class), props,
                autoDispatch, mock(com.fixbridge.job.JobRepository.class));

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setCustomerId(customerId);
        job.setStatus(JobStatus.proposal_sent);
        when(jobService.requireJob(job.getId())).thenReturn(job);
    }

    private Proposal approvedProposal(long retailCents, long depositCents) {
        Proposal p = new Proposal();
        p.setId(UUID.randomUUID());
        p.setJobId(job.getId());
        p.setRetailTotalCents(retailCents);
        p.setDepositCents(depositCents);
        when(proposals.findById(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    private Payment repairPayment(PaymentStatus status, long amountCents) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setJobId(job.getId());
        p.setCustomerId(customerId);
        p.setType(PaymentType.managed_repair);
        p.setStatus(status);
        p.setAmountCents(amountCents);
        p.setStripeCheckoutSession("cs_stub_" + p.getId().toString().replace("-", ""));
        when(payments.findByStripeCheckoutSession(p.getStripeCheckoutSession())).thenReturn(Optional.of(p));
        return p;
    }

    // ---- Opening the checkout (tests 1 & 3) ----

    @Test
    void approvingOpensACheckoutForTheAmountTheCustomerWasQuoted() {
        Proposal p = approvedProposal(46_505L, 0L);
        when(stripe.createCheckout(any(), anyLong(), any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_test", "http://pay"));

        var view = service.createRepairCheckout(customer, p.getId());

        assertThat(view.amountCents()).isEqualTo(46_505L);
        verify(stripe).createCheckout(eq(PaymentType.managed_repair), eq(46_505L), eq("USD"), any());
    }

    @Test
    void aDepositIsChargedInsteadOfTheFullAmountWhenOneIsSet() {
        Proposal p = approvedProposal(46_505L, 10_000L);
        when(stripe.createCheckout(any(), anyLong(), any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_test", "http://pay"));

        assertThat(service.createRepairCheckout(customer, p.getId()).amountCents()).isEqualTo(10_000L);
    }

    @Test
    void openingACheckoutMovesNoMoneyAndDoesNotAdvanceTheJob() {
        // A checkout is an invitation to pay. Nothing has been paid yet.
        Proposal p = approvedProposal(46_505L, 0L);
        when(stripe.createCheckout(any(), anyLong(), any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_test", "http://pay"));

        service.createRepairCheckout(customer, p.getId());

        verify(jobService, never()).transition(any(), any(), any());
        verifyNoInteractions(transfers);
    }

    @Test
    void somebodyElseCannotOpenACheckoutOnYourJob() {
        Proposal p = approvedProposal(46_505L, 0L);
        AuthUser stranger = new AuthUser(UUID.randomUUID(), "x@example.test", List.of(UserRole.customer));

        assertThatThrownBy(() -> service.createRepairCheckout(stranger, p.getId()))
                .isInstanceOf(ApiException.class);
        verify(stripe, never()).createCheckout(any(), anyLong(), any(), any());
    }

    // ---- A confirmed payment (test 4) ----

    @Test
    void aConfirmedRepairPaymentSchedulesTheJob() {
        Payment paid = repairPayment(PaymentStatus.requires_payment, 46_505L);

        service.handlePaidCheckout(paid.getStripeCheckoutSession());

        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.succeeded);
        verify(jobService).transition(eq(job), eq(JobStatus.approved), any());
        verify(jobService).transition(eq(job), eq(JobStatus.scheduled), any());
    }

    // ---- A payment that never confirmed (test 5) ----

    @Test
    void anUnpaidCheckoutLeavesTheJobWhereItWas() {
        // The job advances on the server confirming payment, never on a client claiming it. Until
        // then the proposal is approved but unpaid, and the job stays put.
        repairPayment(PaymentStatus.requires_payment, 46_505L);

        assertThat(job.getStatus()).isEqualTo(JobStatus.proposal_sent);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aFailedPaymentNeverSchedulesTheJob() {
        Payment failed = repairPayment(PaymentStatus.failed, 46_505L);

        assertThatThrownBy(() -> service.completeStubCheckout(customer, failed.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);

        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.failed);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aCancelledCheckoutNeverSchedulesTheJob() {
        Payment cancelled = repairPayment(PaymentStatus.canceled, 46_505L);

        assertThatThrownBy(() -> service.completeStubCheckout(customer, cancelled.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void anUnknownSessionChangesNothing() {
        when(payments.findByStripeCheckoutSession("cs_never_existed")).thenReturn(Optional.empty());

        service.handlePaidCheckout("cs_never_existed");

        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Repeats (tests 6 & 7) ----

    @Test
    void aRepeatedWebhookDoesNotScheduleTheJobTwice() {
        // Stripe retries. Two transitions would write a second history entry and, worse, would
        // re-run whatever the transition triggers.
        Payment paid = repairPayment(PaymentStatus.requires_payment, 46_505L);

        service.handlePaidCheckout(paid.getStripeCheckoutSession());
        service.handlePaidCheckout(paid.getStripeCheckoutSession());

        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.approved), any());
        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.scheduled), any());
    }

    @Test
    void aDoubleClickOnPayDoesNotChargeTwice() {
        Payment paid = repairPayment(PaymentStatus.requires_payment, 46_505L);

        service.completeStubCheckout(customer, paid.getStripeCheckoutSession());
        service.completeStubCheckout(customer, paid.getStripeCheckoutSession());

        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.succeeded);
        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.scheduled), any());
    }

    // ---- The contractor is still owed nothing (test 8) ----

    @Test
    void payingForTheRepairReleasesNoPayout() {
        // The customer's money arriving is not the contractor earning it. Payout is gated on
        // completed, approved work — a separate stage with its own checks.
        Payment paid = repairPayment(PaymentStatus.requires_payment, 46_505L);

        service.handlePaidCheckout(paid.getStripeCheckoutSession());

        verifyNoInteractions(transfers);
        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void aScheduledJobIsStillTooEarlyForAPayout() {
        // The state a paid job lands in. Asserted explicitly because "paid" reading as "payable" is
        // exactly the mistake that would pay a contractor who has not turned up yet.
        job.setStatus(JobStatus.scheduled);
        AuthUser admin = new AuthUser(UUID.randomUUID(), "a@example.test", List.of(UserRole.admin));

        assertThatThrownBy(() -> service.releasePayout(admin, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(stripe, never()).createTransfer(any(), anyLong(), any(), any());
    }

    @Test
    void theVisitFeeIsNotCapturedByTheRepairPayment() {
        // Two separate pots: the contractor's visit fee and the repair price. Paying one must not
        // reach into the other.
        Payment paid = repairPayment(PaymentStatus.requires_payment, 46_505L);

        service.handlePaidCheckout(paid.getStripeCheckoutSession());

        verify(stripe, never()).captureAuthorization(any(), anyLong());
    }
}
