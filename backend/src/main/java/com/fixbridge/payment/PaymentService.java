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
    private final com.fixbridge.audit.AuditService audit;

    public PaymentService(PaymentRepository payments, DispatchFeeRepository dispatchFees,
                          ProposalRepository proposals, BidRepository bids, ContractorRepository contractors,
                          TransferRepository transfers, StripeClient stripe, JobService jobService,
                          com.fixbridge.notification.NotificationService notifications,
                          com.fixbridge.audit.AuditService audit) {
        this.payments = payments;
        this.dispatchFees = dispatchFees;
        this.proposals = proposals;
        this.bids = bids;
        this.contractors = contractors;
        this.transfers = transfers;
        this.stripe = stripe;
        this.jobService = jobService;
        this.notifications = notifications;
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
            }
            case managed_repair, deposit, final_payment, progress -> {
                jobService.transition(job, JobStatus.approved, null);
                jobService.transition(job, JobStatus.scheduled, null);
            }
            default -> log.info("No job transition for payment type {}", payment.getType());
        }
    }

    /**
     * Admin releases the contractor payout AFTER completion is approved — separate charges & transfers.
     * Funds are transferred only now, not at customer charge time.
     */
    @Transactional
    public PaymentDtos.PayoutView releasePayout(AuthUser admin, UUID jobId) {
        Job job = jobService.requireJob(jobId);
        if (job.getStatus() != JobStatus.work_completed && job.getStatus() != JobStatus.admin_review_pending) {
            throw ApiException.conflict("Payout can only be released after work is completed and approved");
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

        jobService.transition(job, JobStatus.payout_pending, admin.id());
        String transferId = stripe.createTransfer(
                contractor.getStripeAccountId(), bid.getNetTotalCents(), "USD", jobId.toString());

        Transfer transfer = new Transfer();
        transfer.setJobId(jobId);
        transfer.setContractorId(contractor.getId());
        transfer.setAmountCents(bid.getNetTotalCents());
        transfer.setStatus(com.fixbridge.common.enums.TransferStatus.paid);
        transfer.setStripeTransferId(transferId);
        transfer.setReleasedBy(admin.id());
        transfers.save(transfer);

        jobService.transition(job, JobStatus.paid_out, admin.id());
        notifications.payoutReleased(contractor.getId(), jobId, bid.getNetTotalCents());
        audit.record(admin.id(), "payout.release", "transfer", transfer.getId(),
                java.util.Map.of("jobId", jobId.toString(), "contractorId", contractor.getId().toString(),
                        "amountCents", bid.getNetTotalCents(), "stripeTransferId", transferId));
        return new PaymentDtos.PayoutView(transfer.getId(), bid.getNetTotalCents(), "paid");
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
