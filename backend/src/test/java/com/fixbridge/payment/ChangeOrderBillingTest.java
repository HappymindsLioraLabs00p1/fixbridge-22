package com.fixbridge.payment;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.PaymentStatus;
import com.fixbridge.common.enums.PaymentType;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.job.ChangeOrder;
import com.fixbridge.job.ChangeOrderRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.pricing.DispatchFeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
 * Charging for work discovered mid-job.
 *
 * <p>An approved change order was never billed. The code promised a final invoice that nobody built,
 * so once the payout began including approved extra work the platform was paying the contractor for
 * it and collecting nothing — the two halves of the same gap, and fixing only one made the imbalance
 * real rather than cancelling out.
 *
 * <p>The rule that matters most is not the happy path but the repeat: a customer must never be billed
 * twice for the same extra work, and a retried checkout must find the charge already open rather than
 * starting another.
 */
class ChangeOrderBillingTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final ChangeOrderRepository changeOrders = mock(ChangeOrderRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final StripeClient stripe = mock(StripeClient.class);

    private final UUID customerId = UUID.randomUUID();
    private final AuthUser customer = new AuthUser(customerId, "c@example.test", List.of(UserRole.customer));

    private PaymentService service;
    private Job job;
    private ChangeOrder order;

    /** Stands in for the payments table: what is saved is what a later lookup finds. */
    private final List<Payment> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        when(payments.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            if (!stored.contains(p)) stored.add(p);
            return p;
        });
        when(payments.findByJobId(any())).thenAnswer(i -> List.copyOf(stored));

        service = new PaymentService(payments, mock(DispatchFeeRepository.class),
                mock(com.fixbridge.proposal.ProposalRepository.class),
                mock(com.fixbridge.job.BidRepository.class),
                mock(com.fixbridge.contractor.ContractorRepository.class),
                mock(TransferRepository.class), stripe, jobService,
                mock(com.fixbridge.notification.NotificationService.class), mock(RefundRepository.class),
                mock(DisputeRepository.class), mock(com.fixbridge.audit.AuditService.class), props,
                mock(com.fixbridge.job.AutoDispatchService.class),
                mock(com.fixbridge.job.JobRepository.class), changeOrders);

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setCustomerId(customerId);
        job.setStatus(JobStatus.work_started);
        when(jobService.requireJob(job.getId())).thenReturn(job);

        order = new ChangeOrder();
        order.setId(UUID.randomUUID());
        order.setJobId(job.getId());
        order.setAddedNetCents(8_000L);
        order.setAddedRetailCents(28_867L);
        order.setStatus(ProposalStatus.approved);
        when(changeOrders.findById(order.getId())).thenReturn(Optional.of(order));

        when(stripe.createCheckout(any(), anyLong(), any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_test_co", "http://pay"));
    }

    // ---- The charge ----

    @Test
    void approvedExtraWorkIsChargedAtItsRetailPrice() {
        var view = service.createChangeOrderCheckout(customer, order.getId());

        assertThat(view.amountCents()).isEqualTo(28_867L);
        verify(stripe).createCheckout(eq(PaymentType.progress), eq(28_867L), eq("USD"), any());
    }

    @Test
    void theAmountComesFromTheChangeOrderNotTheRequest() {
        // Nothing in the request carries a price. A caller-supplied figure would let a customer name
        // what they pay for their own extra work.
        service.createChangeOrderCheckout(customer, order.getId());

        assertThat(stored).singleElement().satisfies(p -> {
            assertThat(p.getAmountCents()).isEqualTo(order.getAddedRetailCents());
            assertThat(p.getChangeOrderId()).isEqualTo(order.getId());
        });
    }

    @Test
    void theContractorsNetIsNeverWhatTheCustomerPays() {
        // 8,000 net, 28,867 retail. Charging the net would hand the customer the platform's margin.
        service.createChangeOrderCheckout(customer, order.getId());

        verify(stripe, never()).createCheckout(any(), eq(8_000L), any(), any());
    }

    // ---- Never twice ----

    @Test
    void aRetriedCheckoutReturnsTheOpenOneRatherThanBillingAgain() {
        var first = service.createChangeOrderCheckout(customer, order.getId());
        clearInvocations(stripe);

        var second = service.createChangeOrderCheckout(customer, order.getId());

        assertThat(second.amountCents()).isEqualTo(first.amountCents());
        verify(stripe, never()).createCheckout(any(), anyLong(), any(), any());
        assertThat(stored).hasSize(1);
    }

    @Test
    void alreadyPaidExtraWorkIsNotChargedAgain() {
        service.createChangeOrderCheckout(customer, order.getId());
        stored.get(0).setStatus(PaymentStatus.succeeded);
        clearInvocations(stripe);

        service.createChangeOrderCheckout(customer, order.getId());

        verify(stripe, never()).createCheckout(any(), anyLong(), any(), any());
        assertThat(stored).hasSize(1);
    }

    // ---- What may not be charged ----

    @Test
    void extraWorkTheCustomerHasNotApprovedCannotBeCharged() {
        order.setStatus(ProposalStatus.sent);

        assertThatThrownBy(() -> service.createChangeOrderCheckout(customer, order.getId()))
                .isInstanceOf(ApiException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void declinedExtraWorkCannotBeCharged() {
        order.setStatus(ProposalStatus.declined);

        assertThatThrownBy(() -> service.createChangeOrderCheckout(customer, order.getId()))
                .isInstanceOf(ApiException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void unpricedExtraWorkCannotBeCharged() {
        // Approved but never published by an admin, so no retail figure exists to bill.
        order.setAddedRetailCents(0);

        assertThatThrownBy(() -> service.createChangeOrderCheckout(customer, order.getId()))
                .isInstanceOf(ApiException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void somebodyElseCannotPayOrSeeAnothersExtraWork() {
        AuthUser stranger = new AuthUser(UUID.randomUUID(), "x@example.test", List.of(UserRole.customer));

        assertThatThrownBy(() -> service.createChangeOrderCheckout(stranger, order.getId()))
                .isInstanceOf(ApiException.class);
        verify(stripe, never()).createCheckout(any(), anyLong(), any(), any());
    }

    // ---- Paying must not rewind the job ----

    @Test
    void payingForExtraWorkLeavesTheJobWhereItIs() {
        // progress payments normally move a job to approved then scheduled. A contractor is standing
        // in the customer's home when this is paid — reporting the job as "scheduled" would tell the
        // customer that work they are watching happen has not started.
        service.createChangeOrderCheckout(customer, order.getId());
        Payment p = stored.get(0);
        when(payments.findByStripeCheckoutSession(p.getStripeCheckoutSession())).thenReturn(Optional.of(p));

        service.handlePaidCheckout(p.getStripeCheckoutSession());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.succeeded);
        verify(jobService, never()).transition(any(), eq(JobStatus.approved), any());
        verify(jobService, never()).transition(any(), eq(JobStatus.scheduled), any());
        assertThat(job.getStatus()).isEqualTo(JobStatus.work_started);
    }
}
