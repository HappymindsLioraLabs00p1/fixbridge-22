package com.fixbridge.admin;

import com.fixbridge.admin.dto.AdminDtos;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.ComplianceService;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Bid;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.notification.NotificationService;
import com.fixbridge.payment.PaymentService;
import com.fixbridge.pricing.JobPricingRepository;
import com.fixbridge.pricing.PricingEngine;
import com.fixbridge.proposal.Proposal;
import com.fixbridge.proposal.ProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * Turning a contractor's confidential bid into a customer proposal.
 *
 * <p>This is where the two prices in the platform are set: what the contractor is paid and what the
 * customer is charged. Both had no test coverage at all. The rules asserted here are the ones that
 * cannot be got wrong quietly — retail is derived server-side from the bid rather than supplied, the
 * customer's proposal never carries the contractor's net, and a bid belonging to another job cannot
 * be used to price this one.
 */
class AdminCreateProposalTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final JobPricingRepository jobPricing = mock(JobPricingRepository.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final BidRepository bids = mock(BidRepository.class);
    private final ProposalRepository proposals = mock(ProposalRepository.class);
    private final PricingEngine pricingEngine = mock(PricingEngine.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final UUID adminId = UUID.randomUUID();
    private final AuthUser admin = new AuthUser(adminId, "admin@fixbridge.test", List.of(UserRole.admin));

    private AdminService service;
    private Job job;
    private Contractor contractor;

    @BeforeEach
    void setUp() {
        service = new AdminService(jobs, jobService, jobPricing, contractors,
                mock(JobInvitationRepository.class), bids, proposals, pricingEngine,
                mock(PaymentService.class), notifications, mock(com.fixbridge.audit.AuditService.class),
                mock(ComplianceService.class));

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setCustomerId(UUID.randomUUID());
        job.setStatus(JobStatus.bid_received);
        when(jobService.requireJob(job.getId())).thenReturn(job);

        contractor = new Contractor();
        contractor.setId(UUID.randomUUID());

        when(jobPricing.findByJobId(any())).thenReturn(Optional.empty());
        when(proposals.findByJobId(any())).thenReturn(List.of());
        when(proposals.save(any())).thenAnswer(i -> {
            Proposal p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        // Retail is whatever the pricing engine says; the point is that it is asked, not assumed.
        when(pricingEngine.retailForNet(anyLong())).thenAnswer(i -> (long) i.getArgument(0) * 2);
    }

    private Bid bidOn(UUID jobId, long netCents) {
        Bid bid = new Bid();
        bid.setId(UUID.randomUUID());
        bid.setJobId(jobId);
        bid.setContractorId(contractor.getId());
        bid.setNetTotalCents(netCents);
        when(bids.findById(bid.getId())).thenReturn(Optional.of(bid));
        return bid;
    }

    private AdminDtos.CreateProposalRequest request(UUID bidId) {
        return new AdminDtos.CreateProposalRequest(bidId, "Replace the P-trap and supply line",
                0L, "2 days", "90-day", null, null);
    }

    // ---- The happy path ----

    @Test
    void anAdminTurnsAValidBidIntoAProposal() {
        Bid bid = bidOn(job.getId(), 21_000L);

        AdminDtos.AdminProposalView view = service.createProposal(admin, job.getId(), request(bid.getId()));

        assertThat(view.contractorNetCents()).isEqualTo(21_000L);
        assertThat(view.retailTotalCents()).isEqualTo(42_000L);
        assertThat(view.marginCents()).isEqualTo(21_000L);
        assertThat(view.status()).isEqualTo(ProposalStatus.sent);
    }

    @Test
    void theRetailPriceIsDerivedFromTheBidRatherThanSupplied() {
        // Nothing in the request carries a price. A caller-supplied retail would let the margin be
        // set per request instead of by the pricing rules.
        Bid bid = bidOn(job.getId(), 30_000L);

        service.createProposal(admin, job.getId(), request(bid.getId()));

        verify(pricingEngine).retailForNet(30_000L);
    }

    @Test
    void theProposalTheCustomerSeesCarriesNoContractorNet() {
        // The saved Proposal is what the customer's endpoint reads from. It has no field for the
        // net, and must not smuggle it into one that exists.
        Bid bid = bidOn(job.getId(), 21_000L);

        service.createProposal(admin, job.getId(), request(bid.getId()));

        var saved = org.mockito.ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).save(saved.capture());
        assertThat(saved.getValue().getRetailTotalCents()).isEqualTo(42_000L);
        assertThat(saved.getValue().getScope()).isEqualTo("Replace the P-trap and supply line");
    }

    @Test
    void theWinningContractorIsAssignedAndTheCustomerToldOnce() {
        Bid bid = bidOn(job.getId(), 21_000L);

        service.createProposal(admin, job.getId(), request(bid.getId()));

        assertThat(job.getAssignedContractorId()).isEqualTo(contractor.getId());
        verify(jobService).transition(eq(job), eq(JobStatus.proposal_sent), eq(adminId));
        verify(notifications).proposalSent(job.getCustomerId(), job.getId(), 42_000L);
    }

    // ---- One live proposal per job ----

    @Test
    void aSecondProposalIsRefusedWhileOneIsStillLive() {
        // Two live proposals mean two prices for one piece of work, and whichever the customer
        // approves is the one that bills them.
        Bid bid = bidOn(job.getId(), 21_000L);
        Proposal live = new Proposal();
        live.setJobId(job.getId());
        live.setStatus(ProposalStatus.sent);
        when(proposals.findByJobId(job.getId())).thenReturn(List.of(live));

        assertThatThrownBy(() -> service.createProposal(admin, job.getId(), request(bid.getId())))
                .isInstanceOf(ApiException.class);

        verify(proposals, never()).save(any());
        verify(notifications, never()).proposalSent(any(), any(), anyLong());
    }

    @Test
    void anAdminCanProposeAgainAfterTheCustomerDeclined() {
        // The whole point of declining. A declined proposal is history and must not block a new price.
        Bid bid = bidOn(job.getId(), 21_000L);
        Proposal declined = new Proposal();
        declined.setJobId(job.getId());
        declined.setStatus(ProposalStatus.declined);
        when(proposals.findByJobId(job.getId())).thenReturn(List.of(declined));

        AdminDtos.AdminProposalView view = service.createProposal(admin, job.getId(), request(bid.getId()));

        assertThat(view.retailTotalCents()).isEqualTo(42_000L);
        verify(proposals).save(any());
    }

    @Test
    void anExpiredProposalDoesNotBlockANewOne() {
        Bid bid = bidOn(job.getId(), 21_000L);
        Proposal expired = new Proposal();
        expired.setJobId(job.getId());
        expired.setStatus(ProposalStatus.expired);
        when(proposals.findByJobId(job.getId())).thenReturn(List.of(expired));

        service.createProposal(admin, job.getId(), request(bid.getId()));

        verify(proposals).save(any());
    }

    @Test
    void theProposalRecordsWhichBidPricedIt() {
        // Without it, "what were we quoted, and by whom" has no answer once the job moves on.
        Bid bid = bidOn(job.getId(), 21_000L);

        service.createProposal(admin, job.getId(), request(bid.getId()));

        var saved = org.mockito.ArgumentCaptor.forClass(Proposal.class);
        verify(proposals).save(saved.capture());
        assertThat(saved.getValue().getBidId()).isEqualTo(bid.getId());
        assertThat(saved.getValue().getJobId()).isEqualTo(job.getId());
    }

    // ---- Invalid bids ----

    @Test
    void aBidBelongingToAnotherJobCannotPriceThisOne() {
        // Otherwise a cheap bid on an unrelated job could be used to underprice this one, or an
        // expensive one to overcharge for it.
        UUID otherJob = UUID.randomUUID();
        Bid foreign = bidOn(otherJob, 21_000L);

        assertThatThrownBy(() -> service.createProposal(admin, job.getId(), request(foreign.getId())))
                .isInstanceOf(ApiException.class);

        verify(proposals, never()).save(any());
        verify(jobService, never()).transition(any(), any(), any());
        assertThat(job.getAssignedContractorId()).isNull();
    }

    @Test
    void aBidThatDoesNotExistIsRefused() {
        UUID missing = UUID.randomUUID();
        when(bids.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createProposal(admin, job.getId(), request(missing)))
                .isInstanceOf(ApiException.class);

        verify(proposals, never()).save(any());
        verify(notifications, never()).proposalSent(any(), any(), anyLong());
    }

    // ---- The admin queue must contain the jobs an admin has to act on ----

    @Test
    void theQueueIncludesJobsCarryingABid() {
        // bid_received was excluded, so a job vanished from the queue the moment a contractor bid on
        // it — into the one state where an admin is the only one who can move it on.
        service.dispatchQueue();

        var statuses = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(jobs).findByStatusIn(statuses.capture());
        assertThat(statuses.getValue()).contains(
                JobStatus.awaiting_contractor, JobStatus.contractor_invited, JobStatus.bid_received);
    }

    @Test
    void theQueueLeavesOutJobsNobodyIsWaitingOn() {
        service.dispatchQueue();

        var statuses = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(jobs).findByStatusIn(statuses.capture());
        assertThat(statuses.getValue()).doesNotContain(
                JobStatus.awaiting_service_payment, JobStatus.work_completed, JobStatus.paid_out);
    }
}
