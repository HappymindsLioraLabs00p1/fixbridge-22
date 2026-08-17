package com.fixbridge.payment;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.PaymentStatus;
import com.fixbridge.common.enums.PaymentType;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.pricing.DispatchFee;
import com.fixbridge.pricing.DispatchFeeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A job must not be stranded by a payment that can never complete, and must not advance on one that
 * did not.
 *
 * <p>Stripe advances a job through its webhook. In stub mode there is no Stripe and no webhook can
 * reach the machine, so a stub checkout left the job at {@code awaiting_service_payment} forever —
 * which reads to a customer as "no contractor is available" rather than "nobody has paid". These
 * cover the stand-in for that webhook, and equally that the stand-in refuses when it should: a
 * cancelled or failed checkout advancing a job would dispatch a contractor against money that never
 * moved.
 */
class StubCheckoutTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final DispatchFeeRepository dispatchFees = mock(DispatchFeeRepository.class);
    private final StripeClient stripe = mock(StripeClient.class);
    private final JobService jobService = mock(JobService.class);
    private final com.fixbridge.job.AutoDispatchService autoDispatch =
            mock(com.fixbridge.job.AutoDispatchService.class);

    private final UUID customerId = UUID.randomUUID();
    private final AuthUser customer = new AuthUser(customerId, "c@example.test", List.of(UserRole.customer));
    private final AuthUser stranger = new AuthUser(UUID.randomUUID(), "x@example.test", List.of(UserRole.customer));

    private PaymentService serviceWithStubMode(boolean stubMode) {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, stubMode),
                null, null, null, null, null);
        // save() assigns the primary key in the real repository, and the caller uses it as the
        // Stripe reference — so the fake has to do the same or the code NPEs on its own payment.
        when(payments.save(any())).thenAnswer(i -> {
            Payment p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        return new PaymentService(payments, dispatchFees, mock(com.fixbridge.proposal.ProposalRepository.class),
                mock(com.fixbridge.job.BidRepository.class), mock(com.fixbridge.contractor.ContractorRepository.class),
                mock(TransferRepository.class), stripe, jobService,
                mock(com.fixbridge.notification.NotificationService.class), mock(RefundRepository.class),
                mock(DisputeRepository.class), mock(com.fixbridge.audit.AuditService.class), props,
                autoDispatch, mock(com.fixbridge.job.JobRepository.class),
                mock(com.fixbridge.job.ChangeOrderRepository.class));
    }

    private Job job(JobStatus status) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setCustomerId(customerId);
        j.setStatus(status);
        when(jobService.requireJob(j.getId())).thenReturn(j);
        return j;
    }

    private Payment checkout(Job job, PaymentStatus status) {
        Payment p = new Payment();
        p.setId(UUID.randomUUID());
        p.setJobId(job.getId());
        p.setCustomerId(customerId);
        p.setType(PaymentType.dispatch_fee);
        p.setStatus(status);
        p.setAmountCents(14_900L);
        p.setStripeCheckoutSession("cs_stub_" + p.getId().toString().replace("-", ""));
        when(payments.findByStripeCheckoutSession(p.getStripeCheckoutSession())).thenReturn(Optional.of(p));
        return p;
    }

    private void feeOf(long customerPriceCents) {
        DispatchFee fee = new DispatchFee();
        fee.setServiceType("weekday_scheduled");
        fee.setCustomerPriceCents(customerPriceCents);
        fee.setContractorVisitCents(10_000L);   // never waived — the contractor is still paid to attend
        when(dispatchFees.findByServiceTypeAndActiveTrue("weekday_scheduled")).thenReturn(Optional.of(fee));
    }

    // ---- A waived FixBridge fee ----

    @Test
    void aWaivedFeeReachesDispatchWithoutACharge() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        feeOf(0);

        var view = service.createDispatchCheckout(customer, job.getId(), "weekday_scheduled");

        // Stripe will not create a session for zero, and a job waiting on one would never dispatch.
        verify(stripe, never()).createCheckout(any(), anyLong(), any(), any());
        assertThat(view.sessionId()).isNull();
        assertThat(view.amountCents()).isZero();
        verify(jobService).transition(eq(job), eq(JobStatus.paid_for_dispatch), any());
        verify(jobService).transition(eq(job), eq(JobStatus.awaiting_contractor), any());
    }

    @Test
    void aWaivedFeeIsStillRecordedAsAPayment() {
        // At zero, so the job's timeline shows the fee was waived rather than showing a gap.
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        feeOf(0);

        service.createDispatchCheckout(customer, job.getId(), "weekday_scheduled");

        var saved = org.mockito.ArgumentCaptor.forClass(Payment.class);
        verify(payments, atLeastOnce()).save(saved.capture());
        Payment recorded = saved.getAllValues().get(saved.getAllValues().size() - 1);
        assertThat(recorded.getStatus()).isEqualTo(PaymentStatus.succeeded);
        assertThat(recorded.getAmountCents()).isZero();
    }

    @Test
    void aPricedFeeDoesNotAdvanceTheJobUntilItIsPaid() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        feeOf(14_900L);
        when(stripe.createCheckout(any(), anyLong(), any(), any()))
                .thenReturn(new StripeClient.CheckoutSession("cs_stub_abc", "http://stub/pay"));

        var view = service.createDispatchCheckout(customer, job.getId(), "weekday_scheduled");

        assertThat(view.amountCents()).isEqualTo(14_900L);
        verify(jobService, never()).transition(any(), eq(JobStatus.awaiting_contractor), any());
    }

    // ---- Completing a stub checkout ----

    @Test
    void aSuccessfulStubCheckoutAdvancesTheJobLikeTheWebhookWould() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        service.completeStubCheckout(customer, p.getStripeCheckoutSession());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.succeeded);
        verify(jobService).transition(eq(job), eq(JobStatus.paid_for_dispatch), any());
        verify(jobService).transition(eq(job), eq(JobStatus.awaiting_contractor), any());
    }

    @Test
    void completingTwiceTransitionsTheJobOnlyOnce() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        service.completeStubCheckout(customer, p.getStripeCheckoutSession());
        service.completeStubCheckout(customer, p.getStripeCheckoutSession());

        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.paid_for_dispatch), any());
        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.awaiting_contractor), any());
    }

    @Test
    void aCancelledCheckoutDoesNotAdvanceTheJob() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        service.cancelStubCheckout(customer, p.getStripeCheckoutSession());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.canceled);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aCancelledCheckoutCannotLaterBeCompleted() {
        // Otherwise a stale browser tab could dispatch a contractor against an abandoned payment.
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.canceled);

        assertThatThrownBy(() -> service.completeStubCheckout(customer, p.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aFailedCheckoutDoesNotAdvanceTheJob() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.failed);

        assertThatThrownBy(() -> service.completeStubCheckout(customer, p.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void cancellingTwiceIsIdempotent() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        service.cancelStubCheckout(customer, p.getStripeCheckoutSession());
        service.cancelStubCheckout(customer, p.getStripeCheckoutSession());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.canceled);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void anAlreadyPaidCheckoutCannotBeCancelled() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.paid_for_dispatch);
        Payment p = checkout(job, PaymentStatus.succeeded);

        assertThatThrownBy(() -> service.cancelStubCheckout(customer, p.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.succeeded);
    }

    // ---- The gates ----

    @Test
    void theStubPathIsRefusedOutrightWhenStripeIsLive() {
        // Production must settle payments through the verified webhook and nothing else.
        PaymentService service = serviceWithStubMode(false);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        assertThatThrownBy(() -> service.completeStubCheckout(customer, p.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.cancelStubCheckout(customer, p.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aCheckoutBelongingToSomebodyElseIsRefused() {
        PaymentService service = serviceWithStubMode(true);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        assertThatThrownBy(() -> service.completeStubCheckout(stranger, p.getStripeCheckoutSession()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void anUnknownSessionIsRefused() {
        PaymentService service = serviceWithStubMode(true);
        when(payments.findByStripeCheckoutSession("cs_stub_nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.completeStubCheckout(customer, "cs_stub_nope"))
                .isInstanceOf(ApiException.class);
    }

    // ---- The real webhook path is unchanged ----

    @Test
    void theWebhookStillAdvancesTheJobWithStripeLive() {
        PaymentService service = serviceWithStubMode(false);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        service.handlePaidCheckout(p.getStripeCheckoutSession());

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.succeeded);
        verify(jobService).transition(eq(job), eq(JobStatus.awaiting_contractor), any());
    }

    @Test
    void theWebhookRemainsIdempotent() {
        PaymentService service = serviceWithStubMode(false);
        Job job = job(JobStatus.awaiting_service_payment);
        Payment p = checkout(job, PaymentStatus.requires_payment);

        service.handlePaidCheckout(p.getStripeCheckoutSession());
        service.handlePaidCheckout(p.getStripeCheckoutSession());

        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.awaiting_contractor), any());
    }
}
