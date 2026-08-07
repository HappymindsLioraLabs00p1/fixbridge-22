package com.fixbridge.contractor;

import com.fixbridge.ai.AiAssessmentEntity;
import com.fixbridge.ai.AiAssessmentRepository;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.InvitationStatus;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.dto.ContractorDtos;
import com.fixbridge.job.Bid;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.CompletionReport;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitation;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.StripeClient;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ContractorService {

    private final ContractorRepository contractors;
    private final JobService jobService;
    private final JobInvitationRepository invitations;
    private final BidRepository bids;
    private final PropertyRepository properties;
    private final AiAssessmentRepository assessments;
    private final StripeClient stripe;
    private final com.fixbridge.notification.NotificationService notifications;
    private final com.fixbridge.job.CompletionReportRepository completionReports;
    private final ComplianceService compliance;
    private final FixBridgeProperties props;

    public ContractorService(ContractorRepository contractors, JobService jobService,
                             JobInvitationRepository invitations, BidRepository bids,
                             PropertyRepository properties, AiAssessmentRepository assessments,
                             StripeClient stripe, com.fixbridge.notification.NotificationService notifications,
                             com.fixbridge.job.CompletionReportRepository completionReports,
                             ComplianceService compliance, FixBridgeProperties props) {
        this.contractors = contractors;
        this.jobService = jobService;
        this.invitations = invitations;
        this.bids = bids;
        this.properties = properties;
        this.assessments = assessments;
        this.stripe = stripe;
        this.notifications = notifications;
        this.completionReports = completionReports;
        this.compliance = compliance;
        this.props = props;
    }

    /**
     * Create/settle the contractor account and Stripe Connect onboarding. In stub mode this completes
     * onboarding immediately so the payout leg of the loop can be exercised; the real flow uses
     * Stripe-hosted Connect onboarding.
     */
    @Transactional
    public ContractorDtos.ContractorView onboard(AuthUser user, ContractorDtos.OnboardRequest req) {
        Contractor contractor = contractors.findByOwnerUserId(user.id()).orElseGet(Contractor::new);
        contractor.setOwnerUserId(user.id());
        contractor.setBusinessName(req.businessName());
        contractor.setContactPhone(req.contactPhone());

        String onboardingUrl = null;
        if (props.ai().stubMode()) {
            // Frontend-first: complete onboarding immediately so the payout leg can be exercised.
            contractor.setStatus(ContractorStatus.approved);
            contractor.setStripeAccountId("acct_stub_" + user.id().toString().replace("-", "").substring(0, 12));
            contractor.setConnectOnboarded(true);
            contractor.setPayoutsEnabled(true);
        } else {
            // Live: create a Stripe-hosted Connect (Express) account and return its onboarding link.
            if (contractor.getStripeAccountId() == null) {
                StripeClient.ConnectAccount acct = stripe.createConnectAccount(user.email());
                contractor.setStripeAccountId(acct.accountId());
                onboardingUrl = acct.onboardingUrl();
            }
            if (contractor.getStatus() == null || contractor.getStatus() == ContractorStatus.draft) {
                contractor.setStatus(ContractorStatus.documents_pending);
            }
        }
        contractor = contractors.save(contractor);
        return new ContractorDtos.ContractorView(contractor.getId(), contractor.getBusinessName(),
                contractor.getStatus(), contractor.isPayoutsEnabled(), onboardingUrl);
    }

    @Transactional(readOnly = true)
    public List<ContractorDtos.InvitationView> myInvitations(AuthUser user) {
        Contractor contractor = requireContractor(user);
        return invitations.findByContractorId(contractor.getId()).stream()
                .map(this::toInvitationView)
                .toList();
    }

    /** Submit a confidential net bid for an invited job. */
    @Transactional
    public void submitBid(AuthUser user, UUID jobId, ContractorDtos.BidRequest req) {
        Contractor contractor = requireContractor(user);
        JobInvitation invitation = invitations.findByJobIdAndContractorId(jobId, contractor.getId())
                .orElseThrow(() -> ApiException.forbidden());
        Job job = jobService.requireJob(jobId);

        Bid bid = new Bid();
        bid.setJobId(jobId);
        bid.setContractorId(contractor.getId());
        bid.setLaborCents(req.laborCents());
        bid.setMaterialsCents(req.materialsCents());
        bid.setEquipmentCents(req.equipmentCents());
        bid.setTravelCents(req.travelCents());
        bid.setPermitCents(req.permitCents());
        bid.setDisposalCents(req.disposalCents());
        bid.setNetTotalCents(req.netTotalCents());
        bid.setEarliestStart(req.earliestStart());
        bid.setDurationDays(req.durationDays());
        bid.setWarranty(req.warranty());
        bid.setExclusions(req.exclusions());
        bids.save(bid);

        invitation.setStatus(InvitationStatus.accepted);
        invitations.save(invitation);
        jobService.transition(job, JobStatus.bid_received, user.id());
    }

    /** Contractor submits completion proof. Customer/admin then confirms before payout. */
    @Transactional
    public void submitCompletion(AuthUser user, UUID jobId, ContractorDtos.CompletionRequest req) {
        Contractor contractor = requireContractor(user);
        Job job = jobService.requireJob(jobId);
        if (!contractor.getId().equals(job.getAssignedContractorId())) {
            throw ApiException.forbidden();
        }

        // Persist the proof itself (FR-JOB-7) — not just the status change.
        CompletionReport report = new CompletionReport();
        report.setJobId(jobId);
        report.setSummary(req.summary());
        report.setMaterialsUsed(req.materialsUsed());
        report.setArrivedAt(req.arrivedAt());
        report.setCompletedAt(req.completedAt() != null ? req.completedAt() : java.time.Instant.now());
        report.setBeforeKeys(toArray(req.beforeKeys()));
        report.setAfterKeys(toArray(req.afterKeys()));
        report.setInvoiceUrl(req.invoiceUrl());
        report.setWarrantyText(req.warrantyText());
        completionReports.save(report);

        jobService.transition(job, JobStatus.work_completed, user.id());
        jobService.transition(job, JobStatus.customer_review_pending, user.id());
        notifications.workCompleted(job.getCustomerId(), jobId);
    }

    private static String[] toArray(java.util.List<String> keys) {
        return keys == null ? new String[0] : keys.toArray(String[]::new);
    }

    /** Upload a compliance document (licence, insurance, workers' comp, W-9). */
    @Transactional
    public com.fixbridge.contractor.dto.ComplianceDtos.DocumentView submitDocument(
            AuthUser user, com.fixbridge.contractor.dto.ComplianceDtos.SubmitDocumentRequest req) {
        return compliance.submit(requireContractor(user).getId(), req);
    }

    @Transactional(readOnly = true)
    public com.fixbridge.contractor.dto.ComplianceDtos.ComplianceStatus myCompliance(AuthUser user) {
        return compliance.statusFor(requireContractor(user).getId());
    }

    private Contractor requireContractor(AuthUser user) {
        return contractors.findByOwnerUserId(user.id())
                .orElseThrow(() -> ApiException.conflict("Complete contractor onboarding first"));
    }

    private ContractorDtos.InvitationView toInvitationView(JobInvitation inv) {
        Job job = jobService.requireJob(inv.getJobId());
        Property property = properties.findById(job.getPropertyId()).orElse(null);
        String area = property == null ? "Service area withheld"
                : String.join(", ", nullSafe(property.getCity()), nullSafe(property.getState()));
        AiAssessmentEntity a = assessments.findFirstByJobIdOrderByCreatedAtDesc(inv.getJobId()).orElse(null);
        return new ContractorDtos.InvitationView(
                inv.getJobId(),
                inv.getStatus(),
                area,
                a == null ? null : a.getRecommendedTrade(),
                a == null ? null : a.getUrgency(),
                inv.getExpectedNetCents());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
