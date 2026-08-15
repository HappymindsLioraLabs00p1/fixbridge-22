package com.fixbridge.contractor;

import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.payment.TransferRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Who may be sent to a customer's home.
 *
 * <p>Approval, compliance and payouts are filters, not ranking factors: an uninsured contractor is
 * not a low-scoring one, and ranking them last would still put them in front of a customer. These
 * assert each gate closes on its own, because a single missing filter is invisible in a result set
 * that looks otherwise reasonable.
 */
class ContractorMatchingServiceTest {

    private final ContractorRepository contractors = mock(ContractorRepository.class);
    private final ContractorSkillRepository skills = mock(ContractorSkillRepository.class);
    private final ContractorReviewRepository reviews = mock(ContractorReviewRepository.class);
    private final TransferRepository transfers = mock(TransferRepository.class);
    private final ComplianceService compliance = mock(ComplianceService.class);

    private final ContractorMatchingService matching =
            new ContractorMatchingService(contractors, skills, reviews, transfers, compliance);

    ContractorMatchingServiceTest() {
        when(reviews.findAll()).thenReturn(List.of());
        when(transfers.findAll()).thenReturn(List.of());
    }

    /** A contractor who should be dispatchable: approved, payouts on, compliant, declares the trade. */
    private Contractor eligible(String business, String trade) {
        Contractor c = new Contractor();
        c.setId(UUID.randomUUID());
        c.setBusinessName(business);
        c.setStatus(ContractorStatus.approved);
        c.setPayoutsEnabled(true);
        c.setLatitude(java.math.BigDecimal.valueOf(40.7));
        c.setLongitude(java.math.BigDecimal.valueOf(-73.9));
        c.setTravelRadiusMiles(25);
        when(compliance.isCompliant(c.getId())).thenReturn(true);
        declare(c, trade);
        return c;
    }

    private void declare(Contractor c, String trade) {
        ContractorSkill s = new ContractorSkill();
        s.setContractorId(c.getId());
        s.setTrade(trade);
        when(skills.findByTradeIgnoreCase(trade)).thenReturn(List.of(s));
    }

    private List<String> matchedNames(String trade) {
        return matching.match(trade, 40.7, -73.9, 10).matches().stream()
                .map(m -> m.businessName()).toList();
    }

    @Test
    void aCompliantApprovedContractorIsMatched() {
        Contractor c = eligible("Kingsway Plumbing", "plumbing");
        when(contractors.findAll()).thenReturn(List.of(c));

        assertThat(matchedNames("plumbing")).containsExactly("Kingsway Plumbing");
    }

    @Test
    void anUnapprovedContractorIsNeverSent() {
        Contractor c = eligible("Not Yet Approved", "plumbing");
        c.setStatus(ContractorStatus.documents_pending);
        when(contractors.findAll()).thenReturn(List.of(c));

        assertThat(matchedNames("plumbing")).isEmpty();
    }

    @Test
    void anUninsuredContractorIsNeverSent() {
        // The whole point of the compliance gate: no licence or insurance, no customer's home.
        Contractor c = eligible("Lapsed Insurance", "plumbing");
        when(compliance.isCompliant(c.getId())).thenReturn(false);
        when(contractors.findAll()).thenReturn(List.of(c));

        assertThat(matchedNames("plumbing")).isEmpty();
    }

    @Test
    void aContractorWhoCannotBePaidIsNeverSent() {
        Contractor c = eligible("No Payouts", "plumbing");
        c.setPayoutsEnabled(false);
        when(contractors.findAll()).thenReturn(List.of(c));

        assertThat(matchedNames("plumbing")).isEmpty();
    }

    @Test
    void aPlumberIsNotOfferedForAnElectricalJob() {
        Contractor plumber = eligible("Kingsway Plumbing", "plumbing");
        Contractor sparks = eligible("Hartline Electric", "electrical");
        when(contractors.findAll()).thenReturn(List.of(plumber, sparks));

        assertThat(matchedNames("electrical")).containsExactly("Hartline Electric");
    }

    @Test
    void whenNobodyDeclaresTheTradeEveryCompliantContractorIsOffered() {
        // A customer with an emergency needs somebody. Reported as an unusable trade filter rather
        // than silently passed off as a precise match.
        Contractor c = eligible("Bedrock Home Repair", "handyman");
        when(contractors.findAll()).thenReturn(List.of(c));
        when(skills.findByTradeIgnoreCase("roofing")).thenReturn(List.of());

        var result = matching.match("roofing", 40.7, -73.9, 10);

        assertThat(result.matches()).hasSize(1);
        assertThat(result.tradeFilterApplied()).isFalse();
    }

    @Test
    void anEmptyResultExplainsItself() {
        when(contractors.findAll()).thenReturn(List.of());
        when(skills.findByTradeIgnoreCase("plumbing")).thenReturn(List.of());

        var result = matching.match("plumbing", 40.7, -73.9, 10);

        assertThat(result.matches()).isEmpty();
        assertThat(result.reason()).isNotBlank();
    }
}
