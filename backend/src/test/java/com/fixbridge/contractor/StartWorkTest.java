package com.fixbridge.contractor;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.StripeClient;
import com.fixbridge.payment.VisitFeeHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A scheduled job had no way forward.
 *
 * <p>Nothing in the application moved a job to work_started except approving a change order, so the
 * only route from "booked" to "in progress" ran through reporting extra work. Meanwhile the
 * contractor's card offered "Mark work complete" on a job nobody had begun, because it read the
 * invitation's status and never the job's.
 *
 * <p>Starting work is a claim that somebody is at a customer's property, so the checks matter as much
 * as the transition: being invited is not being assigned, and a job that is finished, cancelled or
 * not yet booked cannot be started at all.
 */
class StartWorkTest {

    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final JobRepository jobs = mock(JobRepository.class);

    private final UUID assignedUserId = UUID.randomUUID();
    private final AuthUser assignedUser =
            new AuthUser(assignedUserId, "assigned@example.test", List.of(UserRole.contractor));
    private final UUID otherUserId = UUID.randomUUID();
    private final AuthUser otherUser =
            new AuthUser(otherUserId, "other@example.test", List.of(UserRole.contractor));

    private Contractor assigned;
    private Contractor other;
    private ContractorService service;
    private Job job;

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        service = new ContractorService(contractors, jobService, mock(JobInvitationRepository.class),
                mock(BidRepository.class), mock(com.fixbridge.property.PropertyRepository.class),
                mock(com.fixbridge.ai.AiAssessmentRepository.class), mock(StripeClient.class),
                mock(com.fixbridge.notification.NotificationService.class),
                mock(com.fixbridge.job.CompletionReportRepository.class),
                mock(ComplianceService.class), props, mock(VisitFeeHoldService.class),
                new TradeVocabulary(), jobs);

        assigned = contractor(assignedUserId);
        other = contractor(otherUserId);

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setStatus(JobStatus.scheduled);
        job.setAssignedContractorId(assigned.getId());
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
    }

    private Contractor contractor(UUID ownerUserId) {
        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        c.setStatus(ContractorStatus.approved);
        when(contractors.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(c));
        return c;
    }

    // ---- Tests 1 & 2 ----

    @Test
    void theAssignedContractorCanStartAScheduledJob() {
        service.startWork(assignedUser, job.getId());

        verify(jobService).transition(eq(job), eq(JobStatus.work_started), eq(assignedUserId));
    }

    // ---- Test 3: not assigned ----

    @Test
    void aContractorWhoIsNotAssignedCannotStartIt() {
        // Several contractors are invited to a job and only one wins it. An invitation is not a
        // licence to turn up.
        assertThatThrownBy(() -> service.startWork(otherUser, job.getId()))
                .isInstanceOf(ApiException.class);

        verify(jobService, never()).transition(any(), any(), any());
        assertThat(job.getStatus()).isEqualTo(JobStatus.scheduled);
    }

    @Test
    void aJobWithNoContractorAssignedCannotBeStarted() {
        job.setAssignedContractorId(null);

        assertThatThrownBy(() -> service.startWork(assignedUser, job.getId()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Test 4: wrong role ----

    @Test
    void somebodyWithNoContractorAccountCannotStartWork() {
        // A customer reaching the endpoint has no contractor record, so there is nothing to match
        // against the assignment. The controller's role check is the first gate; this is the second.
        UUID customerId = UUID.randomUUID();
        AuthUser customer = new AuthUser(customerId, "c@example.test", List.of(UserRole.customer));
        when(contractors.findByOwnerUserId(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startWork(customer, job.getId()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Tests 5 & 6: wrong state ----

    @Test
    void aCancelledJobCannotBeStarted() {
        job.setStatus(JobStatus.canceled);

        assertThatThrownBy(() -> service.startWork(assignedUser, job.getId()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aCompletedJobCannotBeStartedAgain() {
        job.setStatus(JobStatus.work_completed);

        assertThatThrownBy(() -> service.startWork(assignedUser, job.getId()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void aJobNotYetBookedCannotBeStarted() {
        // Nobody has paid for the repair, so no contractor should be on site.
        job.setStatus(JobStatus.proposal_sent);

        assertThatThrownBy(() -> service.startWork(assignedUser, job.getId()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Tests 7 & 8: repeats and races ----

    @Test
    void startingTwiceRecordsOneStart() {
        // A tap on a phone with a poor connection is easily two requests. The job's timeline must
        // not show it starting twice.
        doAnswer(i -> {
            job.setStatus(JobStatus.work_started);
            return null;
        }).when(jobService).transition(any(), eq(JobStatus.work_started), any());

        service.startWork(assignedUser, job.getId());
        service.startWork(assignedUser, job.getId());

        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.work_started), any());
    }

    @Test
    void anAlreadyStartedJobReportsSuccessRatherThanAnError() {
        // The caller wanted the job started and it is started. Failing here would make a retry look
        // like a fault to a contractor standing at the door.
        job.setStatus(JobStatus.work_started);

        service.startWork(assignedUser, job.getId());

        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void theJobIsReadUnderALockSoConcurrentStartsCannotRace() {
        // Both requests would otherwise read "scheduled" and both write "work_started". The lock is
        // what makes the second one observe the first, which is what makes the guard above work.
        service.startWork(assignedUser, job.getId());

        verify(jobs).findByIdForUpdate(job.getId());
        verify(jobs, never()).findById(job.getId());
    }

    @Test
    void anUnknownJobIsRefused() {
        UUID missing = UUID.randomUUID();
        when(jobs.findByIdForUpdate(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startWork(assignedUser, missing))
                .isInstanceOf(ApiException.class);
    }
}
