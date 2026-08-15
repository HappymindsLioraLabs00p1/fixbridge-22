package com.fixbridge.proposal;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.ProposalStatus;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobService;
import com.fixbridge.payment.PaymentService;
import com.fixbridge.payment.dto.PaymentDtos;
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
 * What a customer may do with a price they have been sent.
 *
 * <p>Two things must stay true whichever way they answer. Nothing is charged by the proposal itself —
 * approving only opens a checkout — and no contractor is paid, because payout is a separate decision
 * made after work is approved. Declining had no implementation at all: {@code ProposalStatus.declined}
 * existed in the enum and in the database, and nothing ever set it, so a customer's only options were
 * to pay or to abandon the job.
 */
class ProposalServiceTest {

    private final ProposalRepository proposals = mock(ProposalRepository.class);
    private final JobService jobService = mock(JobService.class);
    private final PaymentService paymentService = mock(PaymentService.class);

    private final ProposalService service = new ProposalService(proposals, jobService, paymentService);

    private final UUID customerId = UUID.randomUUID();
    private final AuthUser customer = new AuthUser(customerId, "c@example.test", List.of(UserRole.customer));
    private final AuthUser stranger = new AuthUser(UUID.randomUUID(), "x@example.test", List.of(UserRole.customer));

    private Job job;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setId(UUID.randomUUID());
        job.setCustomerId(customerId);
        job.setStatus(JobStatus.proposal_sent);
        when(jobService.requireJob(job.getId())).thenReturn(job);
        when(proposals.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private Proposal proposal(ProposalStatus status) {
        Proposal p = new Proposal();
        p.setId(UUID.randomUUID());
        p.setJobId(job.getId());
        p.setBidId(UUID.randomUUID());
        p.setStatus(status);
        p.setRetailTotalCents(46_505L);
        p.setScope("Replace the P-trap and supply line");
        when(proposals.findById(p.getId())).thenReturn(Optional.of(p));
        when(proposals.findByJobId(job.getId())).thenReturn(List.of(p));
        return p;
    }

    // ---- Viewing (tests 6 & 7) ----

    @Test
    void theCustomerSeesTheirProposal() {
        Proposal p = proposal(ProposalStatus.sent);

        var views = service.listForCustomer(customer, job.getId());

        assertThat(views).hasSize(1);
        assertThat(views.get(0).retailTotalCents()).isEqualTo(46_505L);
        assertThat(views.get(0).scope()).isEqualTo("Replace the P-trap and supply line");
        assertThat(views.get(0).proposalId()).isEqualTo(p.getId());
    }

    @Test
    void theCustomerViewCarriesNoContractorNetAndNoMargin() {
        // The whole platform model depends on this: the customer is quoted one retail figure, and
        // the contractor's price and our margin are ours. The view is a record, so its component
        // list is the guarantee — anything added to it later shows up here.
        proposal(ProposalStatus.sent);

        var view = service.listForCustomer(customer, job.getId()).get(0);

        assertThat(view.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("proposalId", "jobId", "scope", "retailTotalCents",
                        "depositCents", "timeline", "warranty", "exclusions", "terms", "status")
                .doesNotContain("contractorNetCents", "marginCents", "bidId", "contractorId");
    }

    @Test
    void somebodyElsesProposalIsNotVisible() {
        proposal(ProposalStatus.sent);

        assertThatThrownBy(() -> service.listForCustomer(stranger, job.getId()))
                .isInstanceOf(ApiException.class);
    }

    // ---- Approving (tests 8, 9, 10) ----

    @Test
    void approvingOpensCheckoutAndPaysNobody() {
        Proposal p = proposal(ProposalStatus.sent);
        when(paymentService.createRepairCheckout(any(), eq(p.getId())))
                .thenReturn(new PaymentDtos.CheckoutView("cs_test", "http://pay", 46_505L, "USD"));

        var checkout = service.approveAndCheckout(customer, p.getId());

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.approved);
        assertThat(p.getApprovedAt()).isNotNull();
        assertThat(checkout.amountCents()).isEqualTo(46_505L);
        // Approving is consent to be charged, not the charge, and certainly not a payout.
        verify(paymentService).createRepairCheckout(any(), eq(p.getId()));
        verify(paymentService, never()).releasePayout(any(), any());
    }

    @Test
    void anAlreadyDeclinedProposalCannotBeApproved() {
        Proposal p = proposal(ProposalStatus.declined);

        assertThatThrownBy(() -> service.approveAndCheckout(customer, p.getId()))
                .isInstanceOf(ApiException.class);

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.declined);
        verify(paymentService, never()).createRepairCheckout(any(), any());
    }

    @Test
    void anAlreadyApprovedProposalCannotBeApprovedAgain() {
        // Otherwise a second checkout is opened for work already paid for.
        Proposal p = proposal(ProposalStatus.approved);

        assertThatThrownBy(() -> service.approveAndCheckout(customer, p.getId()))
                .isInstanceOf(ApiException.class);
        verify(paymentService, never()).createRepairCheckout(any(), any());
    }

    @Test
    void somebodyElseCannotApproveYourProposal() {
        Proposal p = proposal(ProposalStatus.sent);

        assertThatThrownBy(() -> service.approveAndCheckout(stranger, p.getId()))
                .isInstanceOf(ApiException.class);
        verify(paymentService, never()).createRepairCheckout(any(), any());
    }

    // ---- Declining (tests 11, 12) ----

    @Test
    void decliningMarksTheProposalAndReturnsTheJobToTheQueue() {
        Proposal p = proposal(ProposalStatus.sent);

        service.decline(customer, p.getId(), "too expensive");

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.declined);
        // Back to bid_received, not closed: the homeowner still has the fault and an admin can put
        // together another price. This is what makes "propose again" possible.
        verify(jobService).transition(eq(job), eq(JobStatus.bid_received), eq(customerId));
    }

    @Test
    void decliningChargesNothingAndPaysNobody() {
        Proposal p = proposal(ProposalStatus.sent);

        service.decline(customer, p.getId(), null);

        verifyNoInteractions(paymentService);
    }

    @Test
    void decliningTwiceIsOneDecision() {
        Proposal p = proposal(ProposalStatus.sent);

        service.decline(customer, p.getId(), null);
        service.decline(customer, p.getId(), null);

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.declined);
        verify(jobService, times(1)).transition(any(), any(), any());
    }

    @Test
    void anApprovedProposalCannotBeDeclined() {
        // It is already paid for or on its way to checkout; declining would strand the payment.
        Proposal p = proposal(ProposalStatus.approved);

        assertThatThrownBy(() -> service.decline(customer, p.getId(), null))
                .isInstanceOf(ApiException.class);

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.approved);
        verify(jobService, never()).transition(any(), any(), any());
    }

    @Test
    void somebodyElseCannotDeclineYourProposal() {
        Proposal p = proposal(ProposalStatus.sent);

        assertThatThrownBy(() -> service.decline(stranger, p.getId(), null))
                .isInstanceOf(ApiException.class);

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.sent);
    }

    @Test
    void decliningAJobThatHasMovedOnDoesNotRewindIt() {
        // A proposal declined after the job progressed some other way must not drag the job
        // backwards into the dispatch queue.
        Proposal p = proposal(ProposalStatus.sent);
        job.setStatus(JobStatus.canceled);

        service.decline(customer, p.getId(), null);

        assertThat(p.getStatus()).isEqualTo(ProposalStatus.declined);
        verify(jobService, never()).transition(any(), any(), any());
    }
}
