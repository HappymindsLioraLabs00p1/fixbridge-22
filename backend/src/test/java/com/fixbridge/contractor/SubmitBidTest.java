package com.fixbridge.contractor;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.InvitationStatus;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.dto.ContractorDtos;
import com.fixbridge.job.Bid;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitation;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.StripeClient;
import com.fixbridge.payment.VisitFeeHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A contractor answers an invitation once.
 *
 * <p>A second bid was not merely an untidy row. The payout selects a contractor's most recent bid,
 * so re-submitting silently changed what the platform paid out, and the admin's bid list offered the
 * same contractor twice with different numbers and no way to tell which was meant.
 */
class SubmitBidTest {

    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final JobInvitationRepository invitations = mock(JobInvitationRepository.class);
    private final BidRepository bids = mock(BidRepository.class);
    private final VisitFeeHoldService visitFeeHolds = mock(VisitFeeHoldService.class);

    private final UUID userId = UUID.randomUUID();
    private final AuthUser user = new AuthUser(userId, "pro@example.test", List.of(UserRole.contractor));

    private Contractor contractor;
    private Job job;
    private JobInvitation invitation;
    private ContractorService service;

    /** Stands in for the bids table: what is saved is what a later lookup finds. */
    private final java.util.List<Bid> stored = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        service = new ContractorService(contractors, jobService, invitations, bids,
                mock(com.fixbridge.property.PropertyRepository.class),
                mock(com.fixbridge.ai.AiAssessmentRepository.class),
                mock(StripeClient.class), mock(com.fixbridge.notification.NotificationService.class),
                mock(com.fixbridge.job.CompletionReportRepository.class),
                mock(ComplianceService.class), props, visitFeeHolds, new TradeVocabulary(), mock(com.fixbridge.job.JobRepository.class));

        contractor = new Contractor();
        contractor.setId(UUID.randomUUID());
        contractor.setStatus(ContractorStatus.approved);
        when(contractors.findByOwnerUserId(userId)).thenReturn(Optional.of(contractor));

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setStatus(JobStatus.contractor_invited);
        when(jobService.requireJob(job.getId())).thenReturn(job);

        invitation = new JobInvitation(job.getId(), contractor.getId(), 15_500L);
        when(invitations.findByJobIdAndContractorId(job.getId(), contractor.getId()))
                .thenReturn(Optional.of(invitation));

        // The repository behaves like the table: saves land, lookups see what landed.
        when(bids.saveAndFlush(any())).thenAnswer(i -> {
            Bid b = i.getArgument(0);
            stored.add(b);
            return b;
        });
        when(bids.findByJobIdAndContractorId(any(), any())).thenAnswer(i -> stored.stream()
                .filter(b -> b.getJobId().equals(i.getArgument(0))
                        && b.getContractorId().equals(i.getArgument(1)))
                .findFirst());
    }

    /** The net is summed server-side from the breakdown, so it is set here via the labour line. */
    private ContractorDtos.BidRequest bidOf(long netCents) {
        return new ContractorDtos.BidRequest(netCents, 0L, 0L, 0L, 0L, 0L,
                null, null, null, null);
    }

    // ---- Test 1: first submission ----

    @Test
    void aFirstBidCreatesExactlyOneRecord() {
        assertThat(stored).isEmpty();               // before: 0 bids

        service.submitBid(user, job.getId(), bidOf(21_000L));

        assertThat(stored).hasSize(1);              // after: 1 bid
        assertThat(stored.get(0).getNetTotalCents()).isEqualTo(21_000L);
    }

    // ---- Test 2: retry ----

    @Test
    void retryingTheSameSubmissionDoesNotCreateAnother() {
        service.submitBid(user, job.getId(), bidOf(21_000L));

        assertThatThrownBy(() -> service.submitBid(user, job.getId(), bidOf(21_000L)))
                .isInstanceOf(ApiException.class);

        assertThat(stored).hasSize(1);              // after retry: still 1 bid
    }

    @Test
    void aSecondBidCannotQuietlyRaiseThePayout() {
        // The payout picks the contractor's most recent bid, so this was the real damage: submit
        // low to win the work, submit high afterwards, get paid the higher figure.
        service.submitBid(user, job.getId(), bidOf(21_000L));

        assertThatThrownBy(() -> service.submitBid(user, job.getId(), bidOf(99_000L)))
                .isInstanceOf(ApiException.class);

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getNetTotalCents()).isEqualTo(21_000L);
    }

    // ---- Test 3: concurrency ----

    @Test
    void twoSimultaneousSubmissionsCannotBothWrite() {
        // Both requests pass the application check before either writes; only the database can
        // settle it. The loser sees the same conflict rather than a raw integrity error.
        doThrow(new DataIntegrityViolationException("bids_job_id_contractor_id_key"))
                .when(bids).saveAndFlush(any());

        assertThatThrownBy(() -> service.submitBid(user, job.getId(), bidOf(21_000L)))
                .isInstanceOf(ApiException.class);

        assertThat(stored).isEmpty();
    }

    // ---- Tests 4 & 5: surrounding state ----

    @Test
    void theInvitationAndJobAdvanceOnTheFirstBid() {
        service.submitBid(user, job.getId(), bidOf(21_000L));

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.accepted);
        verify(jobService).transition(eq(job), eq(JobStatus.bid_received), any());
    }

    @Test
    void aRejectedRetryDoesNotDisturbTheInvitationOrTheJob() {
        service.submitBid(user, job.getId(), bidOf(21_000L));
        clearInvocations(jobService);

        assertThatThrownBy(() -> service.submitBid(user, job.getId(), bidOf(21_000L)))
                .isInstanceOf(ApiException.class);

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.accepted);
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Test 7: the visit fee is untouched by any of this ----

    @Test
    void theVisitFeeIsCapturedOnceOnTheFirstBidOnly() {
        service.submitBid(user, job.getId(), bidOf(21_000L));
        assertThatThrownBy(() -> service.submitBid(user, job.getId(), bidOf(21_000L)))
                .isInstanceOf(ApiException.class);

        verify(visitFeeHolds, times(1)).capture(job.getId());
    }

    @Test
    void anAcceptanceStillSucceedsWhenTheVisitFeeCaptureFails() {
        // Capture is deliberately non-fatal: the contractor accepted, and the homeowner's payment
        // state is not their problem.
        when(visitFeeHolds.capture(any())).thenThrow(new RuntimeException("stripe down"));

        service.submitBid(user, job.getId(), bidOf(21_000L));

        assertThat(stored).hasSize(1);
        verify(jobService).transition(eq(job), eq(JobStatus.bid_received), any());
    }

    // ---- The invitation remains the gate ----

    @Test
    void aContractorWhoWasNeverInvitedCannotBid() {
        when(invitations.findByJobIdAndContractorId(job.getId(), contractor.getId()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submitBid(user, job.getId(), bidOf(21_000L)))
                .isInstanceOf(ApiException.class);
        assertThat(stored).isEmpty();
    }
}
