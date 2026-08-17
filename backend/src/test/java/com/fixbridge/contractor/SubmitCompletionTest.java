package com.fixbridge.contractor;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.dto.ContractorDtos;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.CompletionReport;
import com.fixbridge.job.CompletionReportRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.notification.NotificationService;
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
 * Declaring the work finished.
 *
 * <p>This is the moment that starts the clock on the contractor being paid, so it must not be
 * reachable from a job that was never started, or from one whose extra work is still waiting on the
 * customer — closing then would bill for work nobody agreed to. Neither was checked: the only
 * condition was that the contractor was assigned.
 */
class SubmitCompletionTest {

    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final JobRepository jobs = mock(JobRepository.class);
    private final CompletionReportRepository reports = mock(CompletionReportRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final VisitFeeHoldService visitFeeHolds = mock(VisitFeeHoldService.class);
    private final StripeClient stripe = mock(StripeClient.class);

    private final UUID assignedUserId = UUID.randomUUID();
    private final AuthUser assignedUser =
            new AuthUser(assignedUserId, "pro@example.test", List.of(UserRole.contractor));
    private final UUID otherUserId = UUID.randomUUID();
    private final AuthUser otherUser =
            new AuthUser(otherUserId, "other@example.test", List.of(UserRole.contractor));

    private ContractorService service;
    private Contractor assigned;
    private Job job;

    @BeforeEach
    void setUp() {
        FixBridgeProperties props = new FixBridgeProperties(
                null, null, null, null,
                new FixBridgeProperties.Stripe(null, null, null, null, null, null, true),
                null, null, null, null, null);

        service = new ContractorService(contractors, jobService, mock(JobInvitationRepository.class),
                mock(BidRepository.class), mock(com.fixbridge.property.PropertyRepository.class),
                mock(com.fixbridge.ai.AiAssessmentRepository.class), stripe, notifications,
                reports, mock(ComplianceService.class), props, visitFeeHolds,
                new TradeVocabulary(), jobs);

        assigned = contractor(assignedUserId);
        contractor(otherUserId);

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setCustomerId(UUID.randomUUID());
        job.setStatus(JobStatus.work_started);
        job.setAssignedContractorId(assigned.getId());
        when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(reports.findFirstByJobIdOrderByCreatedAtDesc(job.getId())).thenReturn(Optional.empty());
        when(reports.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Contractor contractor(UUID ownerUserId) {
        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        c.setStatus(ContractorStatus.approved);
        when(contractors.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(c));
        return c;
    }

    private ContractorDtos.CompletionRequest request() {
        return new ContractorDtos.CompletionRequest(
                "Replaced the P-trap and supply line, pressure tested",
                "1x P-trap, 1x braided supply line", null, null,
                List.of("before/1.jpg"), List.of("after/1.jpg"), null, "90-day");
    }

    // ---- Test 7 & 11: the valid path ----

    @Test
    void theAssignedContractorCanCompleteWorkThatWasStarted() {
        service.submitCompletion(assignedUser, job.getId(), request());

        verify(reports).save(argThat(r -> r.getJobId().equals(job.getId())));
        verify(jobService).transition(eq(job), eq(JobStatus.work_completed), eq(assignedUserId));
        verify(jobService).transition(eq(job), eq(JobStatus.customer_review_pending), eq(assignedUserId));
        verify(notifications).workCompleted(job.getCustomerId(), job.getId());
    }

    @Test
    void completingTheWorkPaysNobody() {
        // Submitting proof starts a review; it moves no money in either direction.
        service.submitCompletion(assignedUser, job.getId(), request());

        verify(stripe, never()).createTransfer(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(visitFeeHolds, never()).capture(any());
    }

    // ---- Test 8: wrong state ----

    @Test
    void aScheduledJobCannotBeCompleted() {
        // Nobody has been on site. Closing here would claim work that never happened.
        job.setStatus(JobStatus.scheduled);

        assertThatThrownBy(() -> service.submitCompletion(assignedUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);

        verify(reports, never()).save(any());
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Test 12: extra work must be resolved first ----

    @Test
    void aJobWaitingOnExtraWorkApprovalCannotBeCompleted() {
        // change_order_pending means the customer has not agreed to the additional cost yet.
        // Completing would bill for work nobody approved.
        job.setStatus(JobStatus.change_order_pending);

        assertThatThrownBy(() -> service.submitCompletion(assignedUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);

        verify(reports, never()).save(any());
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Test 9: wrong contractor ----

    @Test
    void aContractorWhoIsNotAssignedCannotCompleteTheJob() {
        assertThatThrownBy(() -> service.submitCompletion(otherUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);

        verify(reports, never()).save(any());
        verify(jobService, never()).transition(any(), any(), any());
    }

    // ---- Test 10: repeats ----

    @Test
    void completingTwiceFilesOneReport() {
        // A retry from a phone that lost signal must not leave the customer two things to sign off,
        // nor satisfy the payout gate with whichever is approved first.
        doAnswer(i -> {
            job.setStatus(JobStatus.customer_review_pending);
            return null;
        }).when(jobService).transition(any(), eq(JobStatus.customer_review_pending), any());

        service.submitCompletion(assignedUser, job.getId(), request());
        // The first submit left both a report and a job past work_started — what the retry sees.
        when(reports.findFirstByJobIdOrderByCreatedAtDesc(job.getId()))
                .thenReturn(Optional.of(new CompletionReport()));
        service.submitCompletion(assignedUser, job.getId(), request());

        verify(reports, times(1)).save(any());
        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.work_completed), any());
    }

    @Test
    void aRepeatAfterCompletionReportsSuccessRatherThanAnError() {
        job.setStatus(JobStatus.customer_review_pending);
        when(reports.findFirstByJobIdOrderByCreatedAtDesc(job.getId()))
                .thenReturn(Optional.of(new CompletionReport()));

        service.submitCompletion(assignedUser, job.getId(), request());

        verify(reports, never()).save(any());
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void theJobIsReadUnderALockSoConcurrentCompletionsCannotRace() {
        service.submitCompletion(assignedUser, job.getId(), request());

        verify(jobs).findByIdForUpdate(job.getId());
    }
}
