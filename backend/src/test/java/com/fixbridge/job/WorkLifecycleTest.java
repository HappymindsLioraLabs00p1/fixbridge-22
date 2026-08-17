package com.fixbridge.job;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.dto.ChangeOrderDtos;
import com.fixbridge.pricing.PricingEngine;
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
 * Work discovered mid-job, and the money it must not move.
 *
 * <p>Extra work is the point in the lifecycle where a customer's agreed price can grow, so the rules
 * around it are the ones a dispute turns on: it can only be raised on a job actually in progress,
 * only one can be outstanding at a time, and reporting it charges nobody. The customer sees the added
 * retail; the contractor's net stays confidential.
 */
class WorkLifecycleTest {

    private final ChangeOrderRepository changeOrders = mock(ChangeOrderRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final PricingEngine pricingEngine = mock(PricingEngine.class);
    private final com.fixbridge.notification.NotificationService notifications =
            mock(com.fixbridge.notification.NotificationService.class);

    private final UUID contractorUserId = UUID.randomUUID();
    private final AuthUser contractorUser =
            new AuthUser(contractorUserId, "pro@example.test", List.of(UserRole.contractor));
    private final UUID otherUserId = UUID.randomUUID();
    private final AuthUser otherUser =
            new AuthUser(otherUserId, "other@example.test", List.of(UserRole.contractor));
    private final UUID customerId = UUID.randomUUID();
    private final AuthUser customer =
            new AuthUser(customerId, "c@example.test", List.of(UserRole.customer));

    private ChangeOrderService service;
    private Contractor assigned;
    private Job job;

    @BeforeEach
    void setUp() {
        service = new ChangeOrderService(changeOrders, jobService, contractors, pricingEngine,
                notifications, mock(com.fixbridge.audit.AuditService.class));

        assigned = contractor(contractorUserId);
        contractor(otherUserId);

        job = new Job();
        job.setId(UUID.randomUUID());
        job.setCustomerId(customerId);
        job.setStatus(JobStatus.work_started);
        job.setAssignedContractorId(assigned.getId());
        when(jobService.requireJob(job.getId())).thenReturn(job);
        when(changeOrders.findByJobIdOrderByCreatedAtAsc(job.getId())).thenReturn(List.of());
        when(changeOrders.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Contractor contractor(UUID ownerUserId) {
        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        when(contractors.findByOwnerUserId(ownerUserId)).thenReturn(Optional.of(c));
        return c;
    }

    private ChangeOrderDtos.SubmitRequest request() {
        return new ChangeOrderDtos.SubmitRequest("Waste pipe behind the unit is cracked", 8_000L, 1);
    }

    private ChangeOrder existing(ProposalStatus status) {
        ChangeOrder co = new ChangeOrder();
        co.setId(UUID.randomUUID());
        co.setJobId(job.getId());
        co.setAddedNetCents(8_000L);
        co.setStatus(status);
        when(changeOrders.findById(co.getId())).thenReturn(Optional.of(co));
        when(changeOrders.findByJobIdOrderByCreatedAtAsc(job.getId())).thenReturn(List.of(co));
        return co;
    }

    // ---- Test 1: the assigned contractor can report extra work ----

    @Test
    void theAssignedContractorCanReportExtraWorkWhileTheJobIsUnderWay() {
        service.submit(contractorUser, job.getId(), request());

        verify(changeOrders).save(argThat(co ->
                co.getAddedNetCents() == 8_000L && co.getStatus() == ProposalStatus.draft));
        verify(jobService).transition(eq(job), eq(JobStatus.change_order_pending), eq(contractorUserId));
    }

    // ---- Test 2: not the assigned contractor ----

    @Test
    void aContractorWhoIsNotAssignedCannotReportExtraWork() {
        assertThatThrownBy(() -> service.submit(otherUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);
        verify(changeOrders, never()).save(any());
    }

    @Test
    void somebodyWithNoContractorAccountCannotReportExtraWork() {
        when(contractors.findByOwnerUserId(customerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(customer, job.getId(), request()))
                .isInstanceOf(ApiException.class);
        verify(changeOrders, never()).save(any());
    }

    // ---- Test 3: only on a job that is actually running ----

    @Test
    void extraWorkCannotBeReportedOnAJobThatHasNotStarted() {
        // Otherwise cost can be attached to a job nobody has attended.
        job.setStatus(JobStatus.scheduled);

        assertThatThrownBy(() -> service.submit(contractorUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);
        verify(changeOrders, never()).save(any());
    }

    @Test
    void extraWorkCannotBeReportedAfterTheJobIsFinished() {
        // The customer has stopped watching by then.
        job.setStatus(JobStatus.work_completed);

        assertThatThrownBy(() -> service.submit(contractorUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);
        verify(changeOrders, never()).save(any());
    }

    // ---- Test 4: one outstanding at a time ----

    @Test
    void aSecondUnresolvedChangeOrderIsRefused() {
        // Two prices to approve for one visit, and the completion gate satisfied by approving either.
        existing(ProposalStatus.sent);

        assertThatThrownBy(() -> service.submit(contractorUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);
        verify(changeOrders, never()).save(any());
    }

    @Test
    void aDoubleClickCannotFileTwoDrafts() {
        // The first submit lands; the table then holds a draft, which is what the second one sees.
        service.submit(contractorUser, job.getId(), request());
        existing(ProposalStatus.draft);

        assertThatThrownBy(() -> service.submit(contractorUser, job.getId(), request()))
                .isInstanceOf(ApiException.class);

        verify(changeOrders, times(1)).save(any());
    }

    @Test
    void anApprovedChangeOrderDoesNotBlockGenuinelyNewExtraWork() {
        // Resolved history must not stop a second, separate discovery later in the same job.
        existing(ProposalStatus.approved);

        service.submit(contractorUser, job.getId(), request());

        verify(changeOrders).save(any());
    }

    // ---- Test 6: reporting extra work charges nobody ----

    @Test
    void reportingExtraWorkChargesTheCustomerNothing() {
        // It is a request for approval, not a bill. The retail figure does not even exist yet — an
        // admin sets it at publish time.
        service.submit(contractorUser, job.getId(), request());

        verify(changeOrders).save(argThat(co -> co.getAddedRetailCents() == 0));
        verify(pricingEngine, never()).retailForNet(anyLong());
        verifyNoInteractions(notifications);
    }

    // ---- Test 5: approval is the customer's, and it resumes the work ----

    @Test
    void theCustomerApprovesAndWorkResumes() {
        ChangeOrder co = existing(ProposalStatus.sent);

        service.approve(customer, co.getId());

        assertThat(co.getStatus()).isEqualTo(ProposalStatus.approved);
        verify(jobService).transition(eq(job), eq(JobStatus.work_started), eq(customerId));
    }

    @Test
    void aContractorCannotApproveTheirOwnExtraWork() {
        ChangeOrder co = existing(ProposalStatus.sent);

        assertThatThrownBy(() -> service.approve(contractorUser, co.getId()))
                .isInstanceOf(ApiException.class);

        assertThat(co.getStatus()).isEqualTo(ProposalStatus.sent);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void anUnpublishedChangeOrderCannotBeApproved() {
        // Still a draft: no retail price has been set, so there is nothing to agree to.
        ChangeOrder co = existing(ProposalStatus.draft);

        assertThatThrownBy(() -> service.approve(customer, co.getId()))
                .isInstanceOf(ApiException.class);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void approvingTwiceResumesTheWorkOnce() {
        ChangeOrder co = existing(ProposalStatus.sent);

        service.approve(customer, co.getId());
        assertThatThrownBy(() -> service.approve(customer, co.getId()))
                .isInstanceOf(ApiException.class);

        verify(jobService, times(1)).transition(eq(job), eq(JobStatus.work_started), any());
    }

    // ---- The customer never sees the contractor's net ----

    @Test
    void theCustomerViewCarriesTheRetailAndNotTheNet() {
        ChangeOrder co = existing(ProposalStatus.sent);
        co.setAddedNetCents(8_000L);
        co.setAddedRetailCents(17_500L);

        var view = service.approve(customer, co.getId());

        assertThat(view.addedRetailCents()).isEqualTo(17_500L);
        assertThat(view.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("addedNetCents", "marginCents");
    }
}
