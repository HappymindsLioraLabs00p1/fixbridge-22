package com.fixbridge.payment;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.PaymentStatus;
import com.fixbridge.common.enums.PaymentType;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Bid;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.dto.PaymentDtos;
import com.fixbridge.pricing.DispatchFee;
import com.fixbridge.pricing.DispatchFeeRepository;
import com.fixbridge.proposal.Proposal;
import com.fixbridge.proposal.ProposalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final DispatchFeeRepository dispatchFees;
    private final ProposalRepository proposals;
    private final BidRepository bids;
    private final ContractorRepository contractors;
    private final TransferRepository transfers;
    private final StripeClient stripe;
    private final JobService jobService;
    private final com.fixbridge.notification.NotificationService notifications;
    private final RefundRepository refunds;
    private final DisputeRepository disputeRepository;
    private final com.fixbridge.audit.AuditService audit;
    private final com.fixbridge.config.FixBridgeProperties props;
    private final com.fixbridge.job.AutoDispatchService autoDispatch;
    private final com.fixbridge.job.JobRepository jobs;
    private final com.fixbridge.job.ChangeOrderRepository changeOrders;

    public PaymentService(PaymentRepository payments, DispatchFeeRepository dispatchFees,
                          ProposalRepository proposals, BidRepository bids, ContractorRepository contractors,
                          TransferRepository transfers, StripeClient stripe, JobService jobService,
                          com.fixbridge.notification.NotificationService notifications,
                          RefundRepository refunds, DisputeRepository disputeRepository,
                          com.fixbridge.audit.AuditService audit,
                          com.fixbridge.config.FixBridgeProperties props,
                          com.fixbridge.job.AutoDispatchService autoDispatch,
                          com.fixbridge.job.JobRepository jobs,
                          com.fixbridge.job.ChangeOrderRepository changeOrders) {
        this.jobs = jobs;
        this.changeOrders = changeOrders;
        this.props = props;
        this.autoDispatch = autoDispatch;
        this.payments = payments;
        this.dispatchFees = dispatchFees;
        this.proposals = proposals;
        this.bids = bids;
        this.contractors = contractors;
        this.transfers = transfers;
        this.stripe = stripe;
        this.jobService = jobService;
        this.notifications = notifications;
        this.refunds = refunds;
        this.disputeRepository = disputeRepository;
        this.audit = audit;
    }

    /** Customer pays the Service Assessment & Dispatch fee before a Managed contractor is dispatched. */
    @Transactional
    public PaymentDtos.CheckoutView createDispatchCheckout(AuthUser user, UUID jobId, String serviceType) {
        Job job = jobService.requireJob(jobId);
        requireOwner(job, user);
        if (job.getStatus() != JobStatus.awaiting_service_payment) {
            throw ApiException.conflict("This job is not awaiting a dispatch payment");
        }
        DispatchFee fee = dispatchFees.findByServiceTypeAndActiveTrue(serviceType)
                .orElseThrow(() -> ApiException.badRequest("Unknown service type"));

        Payment payment = newPayment(job.getId(), user.id(), PaymentType.dispatch_fee, fee.getCustomerPriceCents());

        // A waived fee still has to reach dispatch.
        //
        // Stripe will not create a Checkout Session for zero, and even where one appeared to
        // succeed the job would sit at awaiting_service_payment forever: the transition to
        // dispatch is driven by the payment webhook, and no webhook fires for money that never
        // moved. A beta promotion would have silently stopped every job from being dispatched.
        //
        // The payment is still recorded, at zero, so the job's timeline shows the fee was waived
        // rather than showing a gap where a payment should be.
        if (fee.getCustomerPriceCents() <= 0) {
            payment.setStatus(PaymentStatus.succeeded);
            payments.save(payment);
            jobService.transition(job, JobStatus.paid_for_dispatch, null);
            jobService.transition(job, JobStatus.awaiting_contractor, null);
            log.info("Dispatch fee waived for job {} — entering dispatch without a charge", jobId);
            dispatchQuietly(job.getId());
            return new PaymentDtos.CheckoutView(null, null, 0L, "USD");
        }

        StripeClient.CheckoutSession session = stripe.createCheckout(
                PaymentType.dispatch_fee, fee.getCustomerPriceCents(), "USD", payment.getId().toString());
        payment.setStripeCheckoutSession(session.sessionId());
        payments.save(payment);
        return new PaymentDtos.CheckoutView(session.sessionId(), session.url(), fee.getCustomerPriceCents(), "USD");
    }

    /** Customer pays the approved retail proposal amount. */
    @Transactional
    public PaymentDtos.CheckoutView createRepairCheckout(AuthUser user, UUID proposalId) {
        Proposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> ApiException.notFound("Proposal"));
        Job job = jobService.requireJob(proposal.getJobId());
        requireOwner(job, user);
        long amount = proposal.getDepositCents() > 0 ? proposal.getDepositCents() : proposal.getRetailTotalCents();

        Payment payment = newPayment(job.getId(), user.id(), PaymentType.managed_repair, amount);
        StripeClient.CheckoutSession session = stripe.createCheckout(
                PaymentType.managed_repair, amount, "USD", payment.getId().toString());
        payment.setStripeCheckoutSession(session.sessionId());
        payments.save(payment);
        return new PaymentDtos.CheckoutView(session.sessionId(), session.url(), amount, "USD");
    }

    /**
     * Marks a checkout as paid and advances the job. Called only from the verified, idempotent webhook
     * handler — never from a client-supplied amount.
     */
    @Transactional
    public void handlePaidCheckout(String sessionId) {
        Payment payment = payments.findByStripeCheckoutSession(sessionId).orElse(null);
        if (payment == null) {
            log.warn("Paid checkout for unknown session {}", sessionId);
            return;
        }
        if (payment.getStatus() == PaymentStatus.succeeded) {
            return; // idempotent
        }
        payment.setStatus(PaymentStatus.succeeded);
        payments.save(payment);

        Job job = jobService.requireJob(payment.getJobId());
        switch (payment.getType()) {
            case dispatch_fee -> {
                jobService.transition(job, JobStatus.paid_for_dispatch, null);
                jobService.transition(job, JobStatus.awaiting_contractor, null);
                dispatchQuietly(job.getId());
            }
            case managed_repair, deposit, final_payment, progress -> {
                jobService.transition(job, JobStatus.approved, null);
                jobService.transition(job, JobStatus.scheduled, null);
            }
            default -> log.info("No job transition for payment type {}", payment.getType());
        }
    }

    /**
     * Ask for contractors now that the job is paid for.
     *
     * <p>Deferred until the payment transaction commits, for two reasons that pull the same way.
     * Dispatch runs in its own transaction and would otherwise not see the transition to
     * awaiting_contractor at all — it would read the job in its previous state and quietly decline to
     * do anything, which is exactly what happened before this was deferred. And inviting contractors
     * from inside the payment's transaction would mean invitations could be created for a payment
     * that then rolled back: contractors called out for a job nobody paid for.
     *
     * <p>Never fatal either way. The money has moved; failing the payment because dispatch found
     * nobody would be the wrong way round. The job stays at awaiting_contractor, which an admin can
     * dispatch from by hand, and the sweep still releases the visit-fee hold if nobody takes it.
     */
    private void dispatchQuietly(UUID jobId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runDispatch(jobId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runDispatch(jobId);
            }
        });
    }

    private void runDispatch(UUID jobId) {
        try {
            autoDispatch.dispatch(jobId);
        } catch (Exception e) {
            log.warn("Auto-dispatch failed for job {} — it stays awaiting a contractor: {}",
                    jobId, e.getMessage());
        }
    }

    // ---- Stub-mode checkout completion ----
    //
    // Stripe advances a job through the webhook, and in stub mode no webhook can ever fire: there is
    // no Stripe to send one. A stub checkout therefore stranded its job at awaiting_service_payment
    // permanently, which reads as "no contractor is available" rather than "nobody has paid".
    //
    // These stand in for the webhook, and only for it. They deliberately do NOT re-implement the
    // transition — they delegate to handlePaidCheckout, so the state a stub payment reaches is the
    // same state a real one reaches, decided in one place. Both are refused outright when Stripe is
    // live, so production behaviour is unchanged.

    /** Completes a stub checkout, standing in for the {@code checkout.session.completed} webhook. */
    @Transactional
    public void completeStubCheckout(AuthUser user, String sessionId) {
        Payment payment = requireOwnStubCheckout(user, sessionId);
        if (payment.getStatus() == PaymentStatus.canceled || payment.getStatus() == PaymentStatus.failed) {
            // A checkout the customer walked away from must not be completable afterwards.
            throw ApiException.conflict("This checkout was cancelled — start a new one");
        }
        // Idempotent by reuse: handlePaidCheckout returns early on an already-succeeded payment, so
        // a double submit cannot transition the job twice.
        handlePaidCheckout(payment.getStripeCheckoutSession());
        log.info("Stub checkout {} completed for job {}", sessionId, payment.getJobId());
    }

    /** Abandons a stub checkout. The job stays exactly where it was — no money moved. */
    @Transactional
    public void cancelStubCheckout(AuthUser user, String sessionId) {
        Payment payment = requireOwnStubCheckout(user, sessionId);
        if (payment.getStatus() == PaymentStatus.succeeded) {
            throw ApiException.conflict("This payment has already completed");
        }
        if (payment.getStatus() == PaymentStatus.canceled) {
            return; // idempotent
        }
        payment.setStatus(PaymentStatus.canceled);
        payments.save(payment);
        log.info("Stub checkout {} cancelled for job {}", sessionId, payment.getJobId());
    }

    /**
     * Ownership and mode gate. The session id alone is never enough: it must belong to a payment on a
     * job this caller owns, exactly as the authenticated endpoints require elsewhere.
     */
    private Payment requireOwnStubCheckout(AuthUser user, String sessionId) {
        if (!props.stripe().stubMode()) {
            // With Stripe live the webhook is the only thing allowed to settle a payment.
            throw ApiException.notFound("Checkout");
        }
        Payment payment = payments.findByStripeCheckoutSession(sessionId)
                .orElseThrow(() -> ApiException.notFound("Checkout"));
        requireOwner(jobService.requireJob(payment.getJobId()), user);
        return payment;
    }

    // ---- Refunds, disputes and payout holds (FR-PAY-9, FR-ADMIN-4) ----

    /** Payments recorded against a job, with how much has already been refunded. Admin only. */
    @Transactional(readOnly = true)
    public java.util.List<PaymentDtos.PaymentView> paymentsForJob(UUID jobId) {
        return payments.findByJobId(jobId).stream().map(p -> {
            long refunded = refunds.findByPaymentId(p.getId()).stream().mapToLong(Refund::getAmountCents).sum();
            boolean disputed = !disputeRepository.findByPaymentId(p.getId()).isEmpty();
            return new PaymentDtos.PaymentView(
                    p.getId(), p.getType().name(), p.getStatus().name(), p.getAmountCents(),
                    refunded, Math.max(0, p.getAmountCents() - refunded), disputed, p.getCreatedAt());
        }).toList();
    }

    /**
     * Refund a succeeded payment, in full or part. The refundable amount is computed server-side from
     * what has already been refunded — a client-supplied figure is never trusted.
     */
    @Transactional
    public PaymentDtos.RefundView refund(AuthUser admin, UUID paymentId, long amountCents, String reason) {
        Payment payment = payments.findById(paymentId).orElseThrow(() -> ApiException.notFound("Payment"));
        if (payment.getStatus() != PaymentStatus.succeeded && payment.getStatus() != PaymentStatus.refunded) {
            throw ApiException.conflict("Only a succeeded payment can be refunded");
        }
        long alreadyRefunded = refunds.findByPaymentId(paymentId).stream().mapToLong(Refund::getAmountCents).sum();
        long refundable = payment.getAmountCents() - alreadyRefunded;
        if (refundable <= 0) {
            throw ApiException.conflict("This payment has already been fully refunded");
        }
        if (amountCents > refundable) {
            throw ApiException.badRequest("Refund exceeds the refundable amount of "
                    + (refundable / 100.0) + " for this payment");
        }

        String stripeRefundId = stripe.createRefund(payment.getStripePaymentIntent(), amountCents, reason);

        Refund refund = new Refund();
        refund.setPaymentId(paymentId);
        refund.setAmountCents(amountCents);
        refund.setReason(reason);
        refund.setStripeRefundId(stripeRefundId);
        refunds.save(refund);

        // Fully refunded → mark the payment (and the job) as refunded.
        if (alreadyRefunded + amountCents >= payment.getAmountCents()) {
            payment.setStatus(PaymentStatus.refunded);
            payments.save(payment);
            if (payment.getJobId() != null) {
                jobService.transition(jobService.requireJob(payment.getJobId()), JobStatus.refunded, admin.id());
            }
        }

        audit.record(admin.id(), "payment.refund", "payment", paymentId,
                java.util.Map.of("amountCents", amountCents, "reason", reason == null ? "" : reason,
                        "stripeRefundId", stripeRefundId));
        return new PaymentDtos.RefundView(refund.getId(), paymentId, amountCents, reason, refund.getCreatedAt());
    }

    /** Record a chargeback from Stripe's webhook and flag the job as disputed. */
    @Transactional
    public void recordDispute(String stripeDisputeId, String paymentIntentId, Long amountCents, String status) {
        if (stripeDisputeId != null && disputeRepository.findByStripeDisputeId(stripeDisputeId).isPresent()) {
            return; // idempotent
        }
        Payment payment = paymentIntentId == null ? null
                : payments.findByStripePaymentIntent(paymentIntentId).orElse(null);
        if (payment == null) {
            log.warn("Dispute {} for unknown payment intent {}", stripeDisputeId, paymentIntentId);
            return;
        }
        Dispute dispute = new Dispute();
        dispute.setPaymentId(payment.getId());
        dispute.setStripeDisputeId(stripeDisputeId);
        dispute.setAmountCents(amountCents);
        dispute.setStatus(status);
        disputeRepository.save(dispute);

        payment.setStatus(PaymentStatus.disputed);
        payments.save(payment);
        if (payment.getJobId() != null) {
            Job job = jobService.requireJob(payment.getJobId());
            // A dispute automatically holds any contractor payout until an admin resolves it.
            job.setPayoutHoldReason("Payment disputed by the customer's bank");
            jobService.transition(job, JobStatus.disputed, null);
        }
    }

    /** Admin places or lifts a hold on a job's contractor payout. */
    @Transactional
    public PaymentDtos.PayoutHoldView setPayoutHold(AuthUser admin, UUID jobId, String reason) {
        Job job = jobService.requireJob(jobId);
        job.setPayoutHoldReason(reason);
        jobService.save(job);
        audit.record(admin.id(), reason == null ? "payout.hold_released" : "payout.hold", "job", jobId,
                java.util.Map.of("reason", reason == null ? "" : reason));
        return new PaymentDtos.PayoutHoldView(jobId, reason != null, reason);
    }

    /**
     * Admin releases the contractor payout AFTER completion is approved — separate charges & transfers.
     * Funds are transferred only now, not at customer charge time.
     */
    @Transactional
    public PaymentDtos.PayoutView releasePayout(AuthUser admin, UUID jobId) {
        // Locked for the whole release. Two admins clicking at the same moment would otherwise both
        // pass every check below before either wrote, and each would send money.
        Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.notFound("Job"));

        // Already paid. Returning the existing transfer rather than failing: the caller wanted this
        // contractor paid for this job and they are, so a retried request is satisfied — and a
        // second transfer is not a stray row, it is a second real payment nobody can recall.
        var existing = transfers.findByJobId(jobId).stream()
                .filter(t -> t.getStatus() == com.fixbridge.common.enums.TransferStatus.paid)
                .findFirst();
        if (existing.isPresent()) {
            Transfer t = existing.get();
            return new PaymentDtos.PayoutView(t.getId(), t.getAmountCents(), t.getStatus().name());
        }

        if (job.getStatus() != JobStatus.work_completed && job.getStatus() != JobStatus.admin_review_pending
                && job.getStatus() != JobStatus.customer_review_pending) {
            throw ApiException.conflict("Payout can only be released after work is completed and approved");
        }
        // FR-JOB-8 / FR-PAY-4: the completion proof must be signed off before any money moves.
        if (!jobService.isCompletionApproved(jobId)) {
            throw ApiException.conflict(
                    "Completion has not been confirmed yet — the customer or an admin must approve the work first");
        }
        // FR-PAY-9: an admin hold (or an open dispute) blocks the payout.
        if (job.getPayoutHoldReason() != null) {
            throw ApiException.conflict("Payout is on hold: " + job.getPayoutHoldReason());
        }
        if (job.getAssignedContractorId() == null) {
            throw ApiException.conflict("No contractor is assigned to this job");
        }
        Contractor contractor = contractors.findById(job.getAssignedContractorId())
                .orElseThrow(() -> ApiException.notFound("Contractor"));
        if (!contractor.isEligibleForWork()) {
            throw ApiException.conflict("Contractor payout onboarding is incomplete");
        }
        Bid bid = bids.findByJobId(jobId).stream()
                .filter(b -> b.getContractorId().equals(contractor.getId()))
                .max(Comparator.comparing(Bid::getCreatedAt))
                .orElseThrow(() -> ApiException.conflict("No contractor bid found for payout"));

        // The bid is what the contractor quoted before opening anything up. Work discovered and
        // approved afterwards is work they actually did, and paying the bid alone left them short by
        // exactly that amount — the customer agreed to the extra, the contractor performed it, and
        // the money stopped at the platform.
        //
        // Only approved change orders count. A declined or expired one is work that was never
        // authorised, and completion sign-off already refuses to proceed while any is still
        // outstanding, so nothing unresolved can reach this point.
        long approvedExtraNet = changeOrders.findByJobIdOrderByCreatedAtAsc(jobId).stream()
                .filter(co -> co.getStatus() == com.fixbridge.common.enums.ProposalStatus.approved)
                .mapToLong(com.fixbridge.job.ChangeOrder::getAddedNetCents)
                .sum();
        long payoutCents = bid.getNetTotalCents() + approvedExtraNet;

        jobService.transition(job, JobStatus.payout_pending, admin.id());
        String transferId = stripe.createTransfer(
                contractor.getStripeAccountId(), payoutCents, "USD", jobId.toString());

        Transfer transfer = new Transfer();
        transfer.setJobId(jobId);
        transfer.setContractorId(contractor.getId());
        transfer.setAmountCents(payoutCents);
        transfer.setStatus(com.fixbridge.common.enums.TransferStatus.paid);
        transfer.setStripeTransferId(transferId);
        transfer.setReleasedBy(admin.id());
        transfers.save(transfer);

        jobService.transition(job, JobStatus.paid_out, admin.id());
        notifications.payoutReleased(contractor.getId(), jobId, payoutCents);
        audit.record(admin.id(), "payout.release", "transfer", transfer.getId(),
                java.util.Map.of("jobId", jobId.toString(), "contractorId", contractor.getId().toString(),
                        "amountCents", payoutCents, "bidNetCents", bid.getNetTotalCents(),
                        "approvedExtraNetCents", approvedExtraNet, "stripeTransferId", transferId));
        return new PaymentDtos.PayoutView(transfer.getId(), payoutCents, "paid");
    }

    private Payment newPayment(UUID jobId, UUID customerId, PaymentType type, long amount) {
        Payment payment = new Payment();
        payment.setJobId(jobId);
        payment.setCustomerId(customerId);
        payment.setType(type);
        payment.setStatus(PaymentStatus.requires_payment);
        payment.setAmountCents(amount);
        return payments.save(payment);
    }

    private void requireOwner(Job job, AuthUser user) {
        if (!job.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
    }
}
