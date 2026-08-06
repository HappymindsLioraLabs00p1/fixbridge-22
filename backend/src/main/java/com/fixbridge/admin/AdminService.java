package com.fixbridge.admin;

import com.fixbridge.admin.dto.AdminDtos;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Bid;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitation;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.PaymentService;
import com.fixbridge.payment.dto.PaymentDtos;
import com.fixbridge.pricing.JobPricing;
import com.fixbridge.pricing.JobPricingRepository;
import com.fixbridge.pricing.PricingEngine;
import com.fixbridge.proposal.Proposal;
import com.fixbridge.proposal.ProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdminService {

    private final JobRepository jobs;
    private final JobService jobService;
    private final JobPricingRepository jobPricing;
    private final ContractorRepository contractors;
    private final JobInvitationRepository invitations;
    private final BidRepository bids;
    private final ProposalRepository proposals;
    private final PricingEngine pricingEngine;
    private final PaymentService paymentService;
    private final com.fixbridge.notification.NotificationService notifications;
    private final com.fixbridge.audit.AuditService audit;

    public AdminService(JobRepository jobs, JobService jobService, JobPricingRepository jobPricing,
                        ContractorRepository contractors, JobInvitationRepository invitations,
                        BidRepository bids, ProposalRepository proposals, PricingEngine pricingEngine,
                        PaymentService paymentService,
                        com.fixbridge.notification.NotificationService notifications,
                        com.fixbridge.audit.AuditService audit) {
        this.jobs = jobs;
        this.jobService = jobService;
        this.jobPricing = jobPricing;
        this.contractors = contractors;
        this.invitations = invitations;
        this.bids = bids;
        this.proposals = proposals;
        this.pricingEngine = pricingEngine;
        this.paymentService = paymentService;
        this.notifications = notifications;
        this.audit = audit;
    }

    /** Jobs that have paid for dispatch and are awaiting contractor assignment. */
    @Transactional(readOnly = true)
    public List<AdminDtos.AdminJobView> dispatchQueue() {
        return jobs.findByStatus(JobStatus.awaiting_contractor).stream()
                .map(this::toAdminJobView)
                .toList();
    }

    /** Contractors the admin can pick from when dispatching, with eligibility spelled out. */
    @Transactional(readOnly = true)
    public List<AdminDtos.ContractorOption> contractorOptions() {
        return contractors.findAll().stream()
                .map(c -> new AdminDtos.ContractorOption(
                        c.getId(), c.getBusinessName(), c.getStatus().name(),
                        c.isEligibleForWork(), ineligibleReason(c)))
                .toList();
    }

    private String ineligibleReason(Contractor c) {
        if (c.getStatus() != ContractorStatus.approved) return "Not approved (" + c.getStatus().name() + ")";
        if (!c.isConnectOnboarded()) return "Stripe Connect onboarding incomplete";
        if (!c.isPayoutsEnabled()) return "Payouts not enabled";
        return null;
    }

    /** Bids submitted for a job, each with the retail/margin the pricing engine would produce. */
    @Transactional(readOnly = true)
    public List<AdminDtos.BidOption> bidOptions(UUID jobId) {
        return bids.findByJobId(jobId).stream()
                .map(b -> {
                    long retail = pricingEngine.retailForNet(b.getNetTotalCents());
                    String name = contractors.findById(b.getContractorId())
                            .map(Contractor::getBusinessName).orElse("Unknown contractor");
                    return new AdminDtos.BidOption(
                            b.getId(), b.getContractorId(), name, b.getNetTotalCents(),
                            retail, retail - b.getNetTotalCents(), b.getDurationDays(), b.getCreatedAt());
                })
                .toList();
    }

    @Transactional
    public void inviteContractor(AuthUser admin, UUID jobId, UUID contractorId) {
        Job job = jobService.requireJob(jobId);
        if (job.getStatus() != JobStatus.awaiting_contractor && job.getStatus() != JobStatus.contractor_invited) {
            throw ApiException.conflict("Job is not ready for contractor invitations");
        }
        Contractor contractor = contractors.findById(contractorId)
                .orElseThrow(() -> ApiException.notFound("Contractor"));
        if (contractor.getStatus() != ContractorStatus.approved) {
            throw ApiException.conflict("Contractor is not approved");
        }
        if (invitations.findByJobIdAndContractorId(jobId, contractorId).isPresent()) {
            throw ApiException.conflict("Contractor already invited to this job");
        }
        Long expectedNet = jobPricing.findByJobId(jobId).map(JobPricing::getEstContractorNetLow).orElse(null);
        invitations.save(new JobInvitation(jobId, contractorId, expectedNet));
        if (job.getStatus() == JobStatus.awaiting_contractor) {
            jobService.transition(job, JobStatus.contractor_invited, admin.id());
        }
        notifications.contractorInvited(contractorId, jobId);
    }

    /** Turn a confidential contractor bid into a customer retail proposal (retail from pricing rules). */
    @Transactional
    public AdminDtos.AdminProposalView createProposal(AuthUser admin, UUID jobId, AdminDtos.CreateProposalRequest req) {
        Job job = jobService.requireJob(jobId);
        Bid bid = bids.findById(req.bidId()).orElseThrow(() -> ApiException.notFound("Bid"));
        if (!bid.getJobId().equals(jobId)) {
            throw ApiException.badRequest("Bid does not belong to this job");
        }

        long retail = pricingEngine.retailForNet(bid.getNetTotalCents());

        Proposal proposal = new Proposal();
        proposal.setJobId(jobId);
        proposal.setStatus(ProposalStatus.sent);
        proposal.setScope(req.scope());
        proposal.setRetailTotalCents(retail);
        proposal.setDepositCents(req.depositCents());
        proposal.setTimeline(req.timeline());
        proposal.setWarranty(req.warranty());
        proposal.setExclusions(req.exclusions());
        proposal.setTerms(req.terms());
        proposals.save(proposal);

        // Assign the winning contractor and record the final net on pricing.
        job.setAssignedContractorId(bid.getContractorId());
        jobPricing.findByJobId(jobId).ifPresent(p -> {
            p.setContractorFinalNetCents(bid.getNetTotalCents());
            p.setCustomerFinalRetailCents(retail);
            jobPricing.save(p);
        });
        jobService.transition(job, JobStatus.proposal_sent, admin.id());
        notifications.proposalSent(job.getCustomerId(), jobId, retail);

        long margin = retail - bid.getNetTotalCents();
        audit.record(admin.id(), "proposal.publish", "proposal", proposal.getId(),
                java.util.Map.of("jobId", jobId.toString(), "contractorNetCents", bid.getNetTotalCents(),
                        "retailTotalCents", retail, "marginCents", margin));
        return new AdminDtos.AdminProposalView(proposal.getId(), jobId, bid.getNetTotalCents(),
                retail, margin, proposal.getStatus());
    }

    @Transactional
    public PaymentDtos.PayoutView releasePayout(AuthUser admin, UUID jobId) {
        return paymentService.releasePayout(admin, jobId);
    }

    private AdminDtos.AdminJobView toAdminJobView(Job job) {
        JobPricing p = jobPricing.findByJobId(job.getId()).orElse(null);
        return new AdminDtos.AdminJobView(
                job.getId(), job.getStatus(), job.getTitle(),
                p == null ? null : p.getAiCategory(),
                p == null ? null : p.getAiUrgency(),
                p == null ? null : p.getEstContractorNetLow(),
                p == null ? null : p.getEstContractorNetHigh(),
                p == null ? null : p.getCustomerRetailLow(),
                p == null ? null : p.getCustomerRetailHigh());
    }
}
