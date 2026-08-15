package com.fixbridge.contractor;

import com.fixbridge.ai.AiAssessmentEntity;
import com.fixbridge.ai.AiAssessmentRepository;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.InvitationStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitation;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.StripeClient;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * What a contractor is shown before they bid.
 *
 * <p>Three invitations to three different jobs rendered identically — same trade, same urgency, same
 * expected net — because the card carried nothing that distinguished one job from another. That is
 * indistinguishable from the same invitation appearing three times, and it is how a contractor ends
 * up bidding on the wrong job.
 */
class InvitationViewTest {

    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final JobInvitationRepository invitations = mock(JobInvitationRepository.class);
    private final PropertyRepository properties = mock(PropertyRepository.class);
    private final AiAssessmentRepository assessments = mock(AiAssessmentRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final AuthUser user = new AuthUser(userId, "pro@example.test", List.of(UserRole.contractor));

    private Contractor contractor;
    private ContractorService service;

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        service = new ContractorService(contractors, jobService, invitations, mock(BidRepository.class),
                properties, assessments, mock(StripeClient.class),
                mock(com.fixbridge.notification.NotificationService.class),
                mock(com.fixbridge.job.CompletionReportRepository.class),
                mock(ComplianceService.class), props,
                mock(com.fixbridge.payment.VisitFeeHoldService.class), new TradeVocabulary());

        contractor = new Contractor();
        contractor.setId(UUID.randomUUID());
        when(contractors.findByOwnerUserId(userId)).thenReturn(Optional.of(contractor));
    }

    /** Registers an invitation to a job whose property has the given city/state. */
    private UUID invite(String city, String state, String trade, InvitationStatus status) {
        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setPropertyId(UUID.randomUUID());
        when(jobService.requireJob(job.getId())).thenReturn(job);

        if (city != null || state != null) {
            Property p = new Property();
            p.setCity(city);
            p.setState(state);
            when(properties.findById(job.getPropertyId())).thenReturn(Optional.of(p));
        } else {
            when(properties.findById(job.getPropertyId())).thenReturn(Optional.empty());
        }

        AiAssessmentEntity a = new AiAssessmentEntity();
        a.setRecommendedTrade(trade);
        when(assessments.findFirstByJobIdOrderByCreatedAtDesc(job.getId())).thenReturn(Optional.of(a));

        JobInvitation inv = new JobInvitation(job.getId(), contractor.getId(), 15_500L);
        inv.setStatus(status);
        return job.getId();
    }

    private void listReturns(UUID... jobIds) {
        List<JobInvitation> list = java.util.Arrays.stream(jobIds)
                .map(id -> {
                    JobInvitation inv = new JobInvitation(id, contractor.getId(), 15_500L);
                    inv.setStatus(InvitationStatus.invited);
                    return inv;
                }).toList();
        when(invitations.findByContractorId(contractor.getId())).thenReturn(list);
    }

    // ---- One invitation per job, and each one identifiable ----

    @Test
    void eachInvitationCarriesItsOwnJobId() {
        UUID a = invite("Queens", "NY", "licensed_plumber", InvitationStatus.invited);
        UUID b = invite("Brooklyn", "NY", "licensed_plumber", InvitationStatus.invited);
        listReturns(a, b);

        var views = service.myInvitations(user);

        assertThat(views).hasSize(2);
        // The job id is what the card turns into a visible reference; without it two plumbing jobs
        // in the same borough are literally the same card twice.
        assertThat(views).extracting(v -> v.jobId()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void oneInvitationPerJobIsListedOnce() {
        UUID job = invite("Queens", "NY", "licensed_plumber", InvitationStatus.invited);
        listReturns(job);

        assertThat(service.myInvitations(user)).hasSize(1);
    }

    // ---- The trade a contractor reads is the catalogue's, not the assessment's ----

    @Test
    void theTradeIsShownInTheCatalogueVocabulary() {
        UUID job = invite("Queens", "NY", "licensed_plumber", InvitationStatus.invited);
        listReturns(job);

        assertThat(service.myInvitations(user).get(0).recommendedTrade()).isEqualTo("plumbing");
    }

    // ---- Missing information reads as missing, not as a rendering fault ----

    @Test
    void aPropertyWithNoCityOrStateSaysSoRatherThanShowingAComma() {
        UUID job = invite("", "", "licensed_plumber", InvitationStatus.invited);
        listReturns(job);

        assertThat(service.myInvitations(user).get(0).generalArea())
                .isEqualTo("Service area withheld");
    }

    @Test
    void aPartialAddressShowsWhatIsKnown() {
        UUID job = invite("Queens", null, "licensed_plumber", InvitationStatus.invited);
        listReturns(job);

        assertThat(service.myInvitations(user).get(0).generalArea()).isEqualTo("Queens");
    }

    @Test
    void aMissingPropertyStillWithholdsTheArea() {
        UUID job = invite(null, null, "licensed_plumber", InvitationStatus.invited);
        listReturns(job);

        assertThat(service.myInvitations(user).get(0).generalArea())
                .isEqualTo("Service area withheld");
    }

    // ---- Lifecycle: an answered invitation keeps saying so ----

    @Test
    void anAcceptedInvitationIsReportedAsAccepted() {
        // The card reads this to stop offering a bid the server would reject. Mutation state alone
        // vanishes on reload, which is why an already-answered invitation looked unanswered again.
        UUID job = invite("Queens", "NY", "licensed_plumber", InvitationStatus.invited);
        JobInvitation accepted = new JobInvitation(job, contractor.getId(), 15_500L);
        accepted.setStatus(InvitationStatus.accepted);
        when(invitations.findByContractorId(contractor.getId())).thenReturn(List.of(accepted));

        assertThat(service.myInvitations(user).get(0).status())
                .isEqualTo(InvitationStatus.accepted);
    }

    @Test
    void everyLifecycleStateSurvivesTheMapping() {
        // declined, expired and the rest are legitimate states; none may be flattened or dropped.
        UUID job = invite("Queens", "NY", "licensed_plumber", InvitationStatus.invited);
        for (InvitationStatus status : InvitationStatus.values()) {
            JobInvitation inv = new JobInvitation(job, contractor.getId(), 15_500L);
            inv.setStatus(status);
            when(invitations.findByContractorId(contractor.getId())).thenReturn(List.of(inv));

            assertThat(service.myInvitations(user).get(0).status()).isEqualTo(status);
        }
    }
}
