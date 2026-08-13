package com.fixbridge.job;

import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.contractor.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The quote a homeowner accepts before anyone is dispatched.
 *
 * <p>The expensive failure is not an arithmetic error, it is a misreading: FixBridge is free during
 * beta, and a customer who takes that to mean the visit is free disputes the contractor's charge
 * after they have already driven out. So these assert the three amounts stay separate, and that a
 * missing rate is reported as unknown rather than as zero.
 */
class DispatchQuoteServiceTest {

    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final ContractorSkillRepository skills = mock(ContractorSkillRepository.class);
    private final ComplianceService compliance = mock(ComplianceService.class);
    private final JobService jobs = mock(JobService.class);
    private final DispatchQuoteService service =
            new DispatchQuoteService(contractors, skills, compliance, new VisitFeeCalculator(), jobs);

    private final UUID jobId = UUID.randomUUID();

    private Contractor contractor(long visitFee) {
        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        c.setStatus(ContractorStatus.approved);
        c.setPayoutsEnabled(true);
        c.setVisitFeeCents(visitFee);
        return c;
    }

    private void given(List<Contractor> list) {
        Job job = new Job();
        job.setId(jobId);
        when(jobs.requireJob(any())).thenReturn(job);
        when(contractors.findAll()).thenReturn(list);
        when(compliance.isCompliant(any())).thenReturn(true);
        when(skills.findByTradeIgnoreCase(any())).thenReturn(List.of());
    }

    @Test
    void theFixbridgeFeeIsZeroDuringBeta() {
        given(List.of(contractor(9_900L)));
        assertThat(service.quoteFor(jobId, null, false).fixbridgeFeeCents()).isZero();
    }

    @Test
    void theVisitFeeIsNotZeroedByTheBetaWaiver() {
        // The whole point: our promotion does not discount the contractor's money.
        given(List.of(contractor(9_900L)));
        var quote = service.quoteFor(jobId, null, false);
        assertThat(quote.fixbridgeFeeCents()).isZero();
        assertThat(quote.visitFeeLowCents()).isEqualTo(9_900L);
    }

    @Test
    void severalContractorsProduceARangeRatherThanOneNumber() {
        // The contractor is not chosen yet; quoting only the cheapest would understate what many
        // homeowners actually pay.
        given(List.of(contractor(9_900L), contractor(14_900L), contractor(12_000L)));
        var quote = service.quoteFor(jobId, null, false);
        assertThat(quote.visitFeeLowCents()).isEqualTo(9_900L);
        assertThat(quote.visitFeeHighCents()).isEqualTo(14_900L);
    }

    @Test
    void noPublishedRateIsReportedAsUnknownNotAsFree() {
        // Showing $0 here would be read as "the visit is free" — the exact misunderstanding this
        // screen exists to prevent.
        given(List.of(contractor(0L)));
        var quote = service.quoteFor(jobId, null, false);
        assertThat(quote.visitFeeKnown()).isFalse();
        assertThat(quote.visitFeeLowCents()).isNull();
        assertThat(quote.explanation()).contains("confirm");
    }

    @Test
    void noEligibleContractorStillReturnsAQuoteRatherThanFailing() {
        given(List.of());
        var quote = service.quoteFor(jobId, null, false);
        assertThat(quote.visitFeeKnown()).isFalse();
        assertThat(quote.availableContractors()).isZero();
    }

    @Test
    void anUncompliantContractorIsExcludedFromTheRange() {
        Contractor ok = contractor(9_900L);
        Contractor uninsured = contractor(4_900L);
        given(List.of(ok, uninsured));
        when(compliance.isCompliant(uninsured.getId())).thenReturn(false);

        var quote = service.quoteFor(jobId, null, false);
        // The cheap uninsured contractor must not set the price the homeowner is quoted.
        assertThat(quote.visitFeeLowCents()).isEqualTo(9_900L);
        assertThat(quote.availableContractors()).isEqualTo(1);
    }

    @Test
    void anEmergencyQuotesTheEmergencyRate() {
        Contractor c = contractor(9_900L);
        c.setEmergencyFeeCents(24_900L);
        given(List.of(c));
        assertThat(service.quoteFor(jobId, null, true).visitFeeLowCents()).isEqualTo(24_900L);
    }

    @Test
    void theQuoteNeverCombinesTheTwoFeesIntoOneNumber() {
        // Separate fields, so no client can render a single total that hides which is which.
        given(List.of(contractor(9_900L)));
        var quote = service.quoteFor(jobId, null, false);
        assertThat(quote.fixbridgeFeeCents()).isZero();
        assertThat(quote.visitFeeLowCents()).isEqualTo(9_900L);
    }
}
