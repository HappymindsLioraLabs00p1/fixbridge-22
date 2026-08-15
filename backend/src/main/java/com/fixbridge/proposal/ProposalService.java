package com.fixbridge.proposal;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.PaymentService;
import com.fixbridge.payment.dto.PaymentDtos;
import com.fixbridge.proposal.dto.ProposalDtos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProposalService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProposalService.class);

    private final ProposalRepository proposals;
    private final JobService jobService;
    private final PaymentService paymentService;

    public ProposalService(ProposalRepository proposals, JobService jobService, PaymentService paymentService) {
        this.proposals = proposals;
        this.jobService = jobService;
        this.paymentService = paymentService;
    }

    @Transactional(readOnly = true)
    public List<ProposalDtos.CustomerProposalView> listForCustomer(AuthUser user, UUID jobId) {
        Job job = jobService.requireJob(jobId);
        if (!job.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        return proposals.findByJobId(jobId).stream().map(this::toCustomerView).toList();
    }

    /** Customer approves the proposal and is sent to Checkout to pay the retail amount. */
    @Transactional
    public PaymentDtos.CheckoutView approveAndCheckout(AuthUser user, UUID proposalId) {
        Proposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> ApiException.notFound("Proposal"));
        Job job = jobService.requireJob(proposal.getJobId());
        if (!job.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        if (proposal.getStatus() != ProposalStatus.sent) {
            throw ApiException.conflict("Proposal is not open for approval");
        }
        proposal.setStatus(ProposalStatus.approved);
        proposal.setApprovedAt(Instant.now());
        proposals.save(proposal);
        return paymentService.createRepairCheckout(user, proposalId);
    }

    /**
     * Customer turns the proposal down.
     *
     * <p>No money moves in either direction: nothing was charged when the proposal was sent, and a
     * contractor is paid only after work is approved. Declining is a pricing decision, not a
     * cancellation — the homeowner still has the fault, and a contractor has still been out to look
     * at it.
     *
     * <p>The job therefore returns to bid_received rather than being closed, which is where an admin
     * picks up a bid and prices it. That is what lets a second proposal be sent, and it is why the
     * one-live-proposal rule counts only proposals that still stand.
     */
    @Transactional
    public void decline(AuthUser user, UUID proposalId, String reason) {
        Proposal proposal = proposals.findById(proposalId)
                .orElseThrow(() -> ApiException.notFound("Proposal"));
        Job job = jobService.requireJob(proposal.getJobId());
        if (!job.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        if (proposal.getStatus() == ProposalStatus.declined) {
            return;   // idempotent: declining twice is one decision, not two
        }
        if (proposal.getStatus() != ProposalStatus.sent) {
            // An approved proposal has already been paid for or is on its way to checkout, and an
            // expired one is no longer the offer on the table.
            throw ApiException.conflict("Only a proposal still open can be declined");
        }
        proposal.setStatus(ProposalStatus.declined);
        proposals.save(proposal);

        log.info("Proposal {} declined by customer{}", proposalId,
                reason == null || reason.isBlank() ? "" : ": " + reason);

        if (job.getStatus() == JobStatus.proposal_sent) {
            jobService.transition(job, JobStatus.bid_received, user.id());
        }
    }

    private ProposalDtos.CustomerProposalView toCustomerView(Proposal p) {
        return new ProposalDtos.CustomerProposalView(
                p.getId(), p.getJobId(), p.getScope(), p.getRetailTotalCents(), p.getDepositCents(),
                p.getTimeline(), p.getWarranty(), p.getExclusions(), p.getTerms(), p.getStatus());
    }
}
