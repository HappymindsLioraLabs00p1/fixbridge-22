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
    private final com.fixbridge.contractor.ComplianceService compliance;

    public AdminService(JobRepository jobs, JobService jobService, JobPricingRepository jobPricing,
                        ContractorRepository contractors, JobInvitationRepository invitations,
                        BidRepository bids, ProposalRepository proposals, PricingEngine pricingEngine,
                        PaymentService paymentService,
                        com.fixbridge.notification.NotificationService notifications,
                        com.fixbridge.audit.AuditService audit,
                        com.fixbridge.contractor.ComplianceService compliance) {
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
        this.compliance = compliance;
    }

    /**
     * Jobs still waiting on something an admin does.
     *
     * <p>Filtering to awaiting_contractor alone made a job vanish from this queue the moment a
     * contractor bid on it: the bid moves the job to bid_received, and bid_received is exactly the
     * state where an admin has to act — turning that bid into a customer proposal. Bids were being
     * submitted into a queue nobody could see.
     *
     * <p>contractor_invited belongs here too. Invitations go out and may all be ignored, and a job
     * nobody answered still needs a person to widen the search.
     */
    /** Proposal states that still stand. Declined and expired are history and block nothing. */
    private static final java.util.Set<ProposalStatus> LIVE = java.util.EnumSet.of(
            ProposalStatus.draft, ProposalStatus.sent, ProposalStatus.approved);

    private static final java.util.Set<JobStatus> NEEDS_ADMIN = java.util.EnumSet.of(
            JobStatus.awaiting_contractor,
            JobStatus.contractor_invited,
            JobStatus.bid_received);

    /** Jobs awaiting contractor assignment, or carrying a bid that needs turning into a proposal. */
    @Transactional(readOnly = true)
    public List<AdminDtos.AdminJobView> dispatchQueue() {
        return jobs.findByStatusIn(NEEDS_ADMIN).stream()
                .map(this::toAdminJobView)
                .toList();
    }

    /** Contractors the admin can pick from when dispatching, with eligibility spelled out. */
    @Transactional(readOnly = true)
    public List<AdminDtos.ContractorOption> contractorOptions() {
        return contractors.findAll().stream()
                .map(c -> {
                    String reason = ineligibleReason(c);
                    return new AdminDtos.ContractorOption(
                            c.getId(), c.getBusinessName(), c.getStatus().name(), reason == null, reason);
                })
                .toList();
    }

    private String ineligibleReason(Contractor c) {
        if (c.getStatus() != ContractorStatus.approved) return "Not approved (" + c.getStatus().name() + ")";
        if (!compliance.isCompliant(c.getId())) return "Licence or insurance missing/expired";
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
        // FR-CON-3: never dispatch someone whose licence or insurance is missing or expired.
        if (!compliance.isCompliant(contractorId)) {
            throw ApiException.conflict(
                    "Contractor cannot be dispatched — required licence or insurance is missing or expired");
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

        // A job carries one live proposal. Two would mean two prices for one piece of work, and
        // whichever the customer approved is the one that would bill them. Declined and expired
        // proposals are history and deliberately do not block a fresh one — re-proposing after a
        // decline is the point of declining.
        boolean alreadyLive = proposals.findByJobId(jobId).stream().anyMatch(p -> LIVE.contains(p.getStatus()));
        if (alreadyLive) {
            throw ApiException.conflict(
                    "This job already has a live proposal — the customer must decline it before another is sent");
        }

        long retail = pricingEngine.retailForNet(bid.getNetTotalCents());

        Proposal proposal = new Proposal();
        proposal.setJobId(jobId);
        proposal.setBidId(bid.getId());
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
