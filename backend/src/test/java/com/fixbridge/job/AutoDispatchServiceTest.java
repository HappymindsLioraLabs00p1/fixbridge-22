package com.fixbridge.job;

import com.fixbridge.ai.AiAssessmentEntity;
import com.fixbridge.ai.AiAssessmentRepository;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.contractor.ContractorMatchingService;
import com.fixbridge.contractor.dto.MatchDtos;
import com.fixbridge.notification.NotificationService;
import com.fixbridge.pricing.JobPricingRepository;
import com.fixbridge.property.PropertyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A paid job must actually reach somebody.
 *
 * <p>Before this existed, reaching {@code awaiting_contractor} set nothing in motion: invitations
 * were only ever created by an admin choosing a contractor by hand, so a paid job waited on a person
 * who had no prompt to act. To the customer that is indistinguishable from nobody being available.
 *
 * <p>The opposite failure matters just as much and is tested here too: inviting twice, or moving a
 * job on when nobody was found, would each claim a contractor the job does not have.
 */
class AutoDispatchServiceTest {

    private final JobService jobService = mock(JobService.class);
    private final ContractorMatchingService matching = mock(ContractorMatchingService.class);
    private final JobInvitationRepository invitations = mock(JobInvitationRepository.class);
    private final AiAssessmentRepository assessments = mock(AiAssessmentRepository.class);
    private final PropertyRepository properties = mock(PropertyRepository.class);
    private final JobPricingRepository jobPricing = mock(JobPricingRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private final AutoDispatchService dispatch = new AutoDispatchService(
            jobService, matching, invitations, assessments, properties, jobPricing, notifications);

    private final UUID plumber = UUID.randomUUID();

    AutoDispatchServiceTest() {
        ReflectionTestUtils.setField(dispatch, "fanOut", 3);
        ReflectionTestUtils.setField(dispatch, "enabled", true);
        when(invitations.findByJobIdAndContractorId(any(), any())).thenReturn(Optional.empty());
        when(jobPricing.findByJobId(any())).thenReturn(Optional.empty());
    }

    private Job job(JobStatus status) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setPropertyId(UUID.randomUUID());
        j.setStatus(status);
        when(jobService.requireJob(j.getId())).thenReturn(j);
        when(properties.findById(j.getPropertyId())).thenReturn(Optional.empty());
        return j;
    }

    private void assessedAs(Job job, String recommendedTrade) {
        AiAssessmentEntity a = new AiAssessmentEntity();
        a.setRecommendedTrade(recommendedTrade);
        when(assessments.findFirstByJobIdOrderByCreatedAtDesc(job.getId())).thenReturn(Optional.of(a));
    }

    private void matchingReturns(UUID... contractorIds) {
        List<MatchDtos.ContractorMatch> matches = java.util.Arrays.stream(contractorIds)
                .map(id -> new MatchDtos.ContractorMatch(id, "Business", "plumbing", 5L,
                        4.5, 10L, 3.0, 5_000L, 25, 80.0, "AVAILABLE"))
                .toList();
        when(matching.match(any(), any(), any(), anyInt()))
                .thenReturn(new MatchDtos.MatchResult("plumbing", matches, null, true));
    }

    private void matchingFindsNobody() {
        when(matching.match(any(), any(), any(), anyInt())).thenReturn(
                new MatchDtos.MatchResult("plumbing", List.of(),
                        "No compliant contractor is available for this trade right now.", true));
    }

    // ---- Test 3: automatic invitation ----

    @Test
    void aJobAwaitingAContractorGetsOneInvited() {
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        matchingReturns(plumber);

        int invited = dispatch.dispatch(job.getId());

        assertThat(invited).isEqualTo(1);
        verify(invitations).save(argThat(i ->
                i.getJobId().equals(job.getId()) && i.getContractorId().equals(plumber)));
        verify(notifications).contractorInvited(plumber, job.getId());
        verify(jobService).transition(eq(job), eq(JobStatus.contractor_invited), any());
    }

    @Test
    void severalContractorsAreInvitedSoOneSilentContractorCannotStallTheJob() {
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        UUID second = UUID.randomUUID();
        matchingReturns(plumber, second);

        assertThat(dispatch.dispatch(job.getId())).isEqualTo(2);
        verify(notifications).contractorInvited(plumber, job.getId());
        verify(notifications).contractorInvited(second, job.getId());
    }

    // ---- Test 1 + 2: the assessment's vocabulary reaches the catalogue's ----

    @Test
    void theAssessmentTradeIsHandedToMatchingUntouchedAndTranslatedThere() {
        // Translation is matching's job, done once for every caller. What matters here is that the
        // recommendation is passed on at all rather than being dropped or replaced.
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        matchingReturns(plumber);

        dispatch.dispatch(job.getId());

        verify(matching).match(eq("licensed_plumber"), any(), any(), anyInt());
    }

    @Test
    void aJobWithNoAssessmentStillDispatches() {
        // An unassessed job has no recommended trade; matching falls back to every compliant
        // contractor rather than nobody, and the customer still gets somebody.
        Job job = job(JobStatus.awaiting_contractor);
        when(assessments.findFirstByJobIdOrderByCreatedAtDesc(job.getId())).thenReturn(Optional.empty());
        matchingReturns(plumber);

        assertThat(dispatch.dispatch(job.getId())).isEqualTo(1);
        verify(matching).match(eq(null), any(), any(), anyInt());
    }

    // ---- Test 4: no duplicate invitations ----

    @Test
    void dispatchingTwiceDoesNotInviteTwice() {
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        matchingReturns(plumber);

        dispatch.dispatch(job.getId());
        // The first run moved the job on, exactly as it would in the database.
        job.setStatus(JobStatus.contractor_invited);
        dispatch.dispatch(job.getId());

        verify(invitations, times(1)).save(any());
        verify(notifications, times(1)).contractorInvited(plumber, job.getId());
    }

    @Test
    void aContractorAlreadyInvitedIsNeverInvitedAgain() {
        // The second guard, independent of job status: an invitation on file is never duplicated.
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        matchingReturns(plumber);
        when(invitations.findByJobIdAndContractorId(job.getId(), plumber))
                .thenReturn(Optional.of(new JobInvitation(job.getId(), plumber, null)));

        assertThat(dispatch.dispatch(job.getId())).isZero();
        verify(invitations, never()).save(any());
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Test 5: nobody found ----

    @Test
    void aJobWithNoEligibleContractorIsNotAssignedAndStaysWaiting() {
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        matchingFindsNobody();

        assertThat(dispatch.dispatch(job.getId())).isZero();
        verify(invitations, never()).save(any());
        verify(notifications, never()).contractorInvited(any(), any());
        verify(jobService, never()).transition(any(), any(), any());
        assertThat(job.getStatus()).isEqualTo(JobStatus.awaiting_contractor);
    }

    @Test
    void aJobNotAwaitingAContractorIsLeftAlone() {
        Job job = job(JobStatus.work_started);

        assertThat(dispatch.dispatch(job.getId())).isZero();
        verify(matching, never()).match(any(), any(), any(), anyInt());
        verify(invitations, never()).save(any());
    }

    @Test
    void autoDispatchCanBeTurnedOff() {
        ReflectionTestUtils.setField(dispatch, "enabled", false);
        Job job = job(JobStatus.awaiting_contractor);

        assertThat(dispatch.dispatch(job.getId())).isZero();
        verify(matching, never()).match(any(), any(), any(), anyInt());
    }

    // ---- A notification outage must not lose the invitation ----

    @Test
    void anInvitationSurvivesAFailedNotification() {
        Job job = job(JobStatus.awaiting_contractor);
        assessedAs(job, "licensed_plumber");
        matchingReturns(plumber);
        doThrow(new RuntimeException("SMS provider down"))
                .when(notifications).contractorInvited(any(), any());

        assertThat(dispatch.dispatch(job.getId())).isEqualTo(1);
        verify(invitations).save(any());
        verify(jobService).transition(eq(job), eq(JobStatus.contractor_invited), any());
    }
}
