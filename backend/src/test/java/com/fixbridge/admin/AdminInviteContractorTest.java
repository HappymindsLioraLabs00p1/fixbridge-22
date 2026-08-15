package com.fixbridge.admin;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.ComplianceService;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobInvitation;
import com.fixbridge.job.JobInvitationRepository;
import com.fixbridge.job.JobRepository;
import com.fixbridge.job.JobService;
import com.fixbridge.notification.NotificationService;
import com.fixbridge.payment.PaymentService;
import com.fixbridge.pricing.JobPricingRepository;
import com.fixbridge.pricing.PricingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Dispatch became automatic; choosing a contractor by hand must still work.
 *
 * <p>It is the fallback whenever automatic dispatch finds nobody, and its gates are the ones that
 * keep an unapproved or uninsured contractor away from a customer's home — so a regression here would
 * be worse than the dead end that prompted the change.
 */
class AdminInviteContractorTest {

    private final JobService jobService = mock(JobService.class);
    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final JobInvitationRepository invitations = mock(JobInvitationRepository.class);
    private final ComplianceService compliance = mock(ComplianceService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final JobPricingRepository jobPricing = mock(JobPricingRepository.class);

    private final AdminService admin = new AdminService(
            mock(JobRepository.class), jobService, jobPricing, contractors, invitations,
            mock(com.fixbridge.job.BidRepository.class),
            mock(com.fixbridge.proposal.ProposalRepository.class),
            mock(PricingEngine.class), mock(PaymentService.class), notifications,
            mock(com.fixbridge.audit.AuditService.class), compliance);

    private final AuthUser adminUser =
            new AuthUser(UUID.randomUUID(), "admin@example.test", List.of(UserRole.admin));

    AdminInviteContractorTest() {
        when(jobPricing.findByJobId(any())).thenReturn(Optional.empty());
        when(invitations.findByJobIdAndContractorId(any(), any())).thenReturn(Optional.empty());
    }

    private Job job(JobStatus status) {
        Job j = new Job();
        j.setId(UUID.randomUUID());
        j.setStatus(status);
        when(jobService.requireJob(j.getId())).thenReturn(j);
        return j;
    }

    private Contractor contractor(ContractorStatus status, boolean compliant) {
        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        c.setStatus(status);
        when(contractors.findById(c.getId())).thenReturn(Optional.of(c));
        when(compliance.isCompliant(c.getId())).thenReturn(compliant);
        return c;
    }

    @Test
    void anAdminCanStillInviteAContractorByHand() {
        Job job = job(JobStatus.awaiting_contractor);
        Contractor c = contractor(ContractorStatus.approved, true);

        admin.inviteContractor(adminUser, job.getId(), c.getId());

        verify(invitations).save(argThat(i -> i.getContractorId().equals(c.getId())));
        verify(jobService).transition(eq(job), eq(JobStatus.contractor_invited), any());
        verify(notifications).contractorInvited(c.getId(), job.getId());
    }

    @Test
    void anAdminCanAddAContractorToAJobAutomaticDispatchAlreadyInvitedTo() {
        // The common case now: auto-dispatch invited three, none replied, an admin adds a fourth.
        // The job is already contractor_invited and must not be transitioned again.
        Job job = job(JobStatus.contractor_invited);
        Contractor c = contractor(ContractorStatus.approved, true);

        admin.inviteContractor(adminUser, job.getId(), c.getId());

        verify(invitations).save(any());
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void thesameContractorIsNotInvitedTwice() {
        Job job = job(JobStatus.awaiting_contractor);
        Contractor c = contractor(ContractorStatus.approved, true);
        when(invitations.findByJobIdAndContractorId(job.getId(), c.getId()))
                .thenReturn(Optional.of(new JobInvitation(job.getId(), c.getId(), null)));

        assertThatThrownBy(() -> admin.inviteContractor(adminUser, job.getId(), c.getId()))
                .isInstanceOf(ApiException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void anUnapprovedContractorIsRefused() {
        Job job = job(JobStatus.awaiting_contractor);
        Contractor c = contractor(ContractorStatus.documents_pending, true);

        assertThatThrownBy(() -> admin.inviteContractor(adminUser, job.getId(), c.getId()))
                .isInstanceOf(ApiException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void aContractorWithLapsedPaperworkIsRefused() {
        Job job = job(JobStatus.awaiting_contractor);
        Contractor c = contractor(ContractorStatus.approved, false);

        assertThatThrownBy(() -> admin.inviteContractor(adminUser, job.getId(), c.getId()))
                .isInstanceOf(ApiException.class);
        verify(invitations, never()).save(any());
    }

    @Test
    void aJobNotReadyForDispatchIsRefused() {
        Job job = job(JobStatus.awaiting_service_payment);
        Contractor c = contractor(ContractorStatus.approved, true);

        assertThatThrownBy(() -> admin.inviteContractor(adminUser, job.getId(), c.getId()))
                .isInstanceOf(ApiException.class);
        verify(invitations, never()).save(any());
    }
}
