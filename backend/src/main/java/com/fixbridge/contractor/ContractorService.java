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
import java.util.stream.Stream;

@Service
public class ContractorService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ContractorService.class);

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

    private final com.fixbridge.payment.VisitFeeHoldService visitFeeHolds;
    private final TradeVocabulary tradeVocabulary;

    public ContractorService(ContractorRepository contractors, JobService jobService,
                             JobInvitationRepository invitations, BidRepository bids,
                             PropertyRepository properties, AiAssessmentRepository assessments,
                             StripeClient stripe, com.fixbridge.notification.NotificationService notifications,
                             com.fixbridge.job.CompletionReportRepository completionReports,
                             ComplianceService compliance, FixBridgeProperties props,
                             com.fixbridge.payment.VisitFeeHoldService visitFeeHolds,
                             TradeVocabulary tradeVocabulary) {
        this.tradeVocabulary = tradeVocabulary;
        this.visitFeeHolds = visitFeeHolds;
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
        if (props.stripe().stubMode()) {
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

        // One bid per contractor per job. A second was not merely an untidy row: the payout picks a
        // contractor's most recent bid, so re-submitting quietly changed what the platform paid out,
        // and the admin's bid list showed the same contractor twice with different numbers.
        //
        // The unique constraint below is what actually guarantees this — two simultaneous submits
        // both pass this check before either writes. It is kept because it answers a double click
        // with a clear message rather than a database error.
        if (bids.findByJobIdAndContractorId(jobId, contractor.getId()).isPresent()) {
            throw ApiException.conflict("You have already submitted a bid for this job");
        }

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
        try {
            // Flushed rather than saved, so a concurrent submit is rejected here by the unique
            // constraint instead of at commit, where it would surface as an opaque 500 after the
            // invitation and job had already been moved on.
            bids.saveAndFlush(bid);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // The other request won. Rolling back is the right outcome — the bid it wrote stands.
            throw ApiException.conflict("You have already submitted a bid for this job");
        }

        invitation.setStatus(InvitationStatus.accepted);
        invitations.save(invitation);
        jobService.transition(job, JobStatus.bid_received, user.id());

        // A contractor has committed to attend, so the visit fee the homeowner authorised is now
        // owed and is taken. Until this point it was only reserved.
        //
        // Deliberately not fatal. The contractor has accepted and the job must proceed; failing
        // the acceptance because a card capture did not go through would punish them for the
        // homeowner's payment problem. The hold stays in place and can be captured or released by
        // an admin — money left reserved is recoverable, a lost acceptance is not.
        try {
            visitFeeHolds.capture(job.getId());
        } catch (Exception e) {
            log.warn("Visit fee capture failed for job {} after contractor acceptance: {}",
                    job.getId(), e.getMessage());
        }
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
        AiAssessmentEntity a = assessments.findFirstByJobIdOrderByCreatedAtDesc(inv.getJobId()).orElse(null);
        return new ContractorDtos.InvitationView(
                inv.getJobId(),
                inv.getStatus(),
                area(property),
                // The catalogue name, not the assessment's "licensed_plumber". A contractor browses
                // the same trades a customer does, and the raw value reads as a database field.
                a == null ? null : tradeVocabulary.toCatalogueTrade(a.getRecommendedTrade()),
                a == null ? null : a.getUrgency(),
                inv.getExpectedNetCents());
    }

    /**
     * Where the work is, at the coarse resolution a contractor sees before bidding — never the full
     * address.
     *
     * <p>Joining blank city and state produced a bare ", ", which reads as a rendering fault rather
     * than as missing information.
     */
    private static String area(Property property) {
        if (property == null) return "Service area withheld";
        String city = nullSafe(property.getCity());
        String state = nullSafe(property.getState());
        String joined = Stream.of(city, state).filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
        return joined.isBlank() ? "Service area withheld" : joined;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
