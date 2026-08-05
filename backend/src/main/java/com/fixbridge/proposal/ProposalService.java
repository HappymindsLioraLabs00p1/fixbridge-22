package com.fixbridge.proposal;

import com.fixbridge.auth.AuthUser;
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

    private ProposalDtos.CustomerProposalView toCustomerView(Proposal p) {
        return new ProposalDtos.CustomerProposalView(
                p.getId(), p.getJobId(), p.getScope(), p.getRetailTotalCents(), p.getDepositCents(),
                p.getTimeline(), p.getWarranty(), p.getExclusions(), p.getTerms(), p.getStatus());
    }
}
