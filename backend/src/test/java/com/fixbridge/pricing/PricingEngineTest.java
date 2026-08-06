package com.fixbridge.pricing;

import com.fixbridge.ai.AssessmentResult;
import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.support.TestFixtures;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricingEngineTest {

    private PricingEngine engineWithDefaultRule() {
        PricingRuleRepository repo = mock(PricingRuleRepository.class);
        when(repo.findFirstByScopeAndActiveTrue("global")).thenReturn(Optional.of(new PricingRule()));
        return new PricingEngine(repo);
    }

    @Test
    void retailForNet_appliesMarginFormula() {
        // Defaults: 25% margin, 2.9% var fee, $75 platform, $50 reserve, $0.30 fixed fee.
        // (53000 + 7500 + 5000 + 30) / (1 - 0.25 - 0.029) = 90888
        assertThat(engineWithDefaultRule().retailForNet(53_000)).isEqualTo(90_888);
    }

    @Test
    void retailForNet_enforcesMinimumGrossProfitFloorOnSmallJobs() {
        PricingEngine engine = engineWithDefaultRule();
        long net = 1_000; // tiny net: the min-profit floor must win over the margin-based price
        long retail = engine.retailForNet(net);
        // Margin-based would be ~18766; min-profit floor yields ~21658.
        assertThat(retail).isEqualTo(21_658);
        assertThat(retail - net).isGreaterThanOrEqualTo(7_500); // at least the minimum gross profit
    }

    @Test
    void preliminaryEstimate_returnsRangeForClearSafeCase() {
        AssessmentResult a = TestFixtures.assessment("plumbing", AiUrgency.low, 0.85, false, 1, 3);
        RetailEstimate e = engineWithDefaultRule().preliminaryEstimate(a);
        assertThat(e.priceAvailable()).isTrue();
        assertThat(e.retailLowCents()).isLessThan(e.retailHighCents());
    }

    @Test
    void preliminaryEstimate_withholdsPriceOnLowConfidence() {
        AssessmentResult a = TestFixtures.assessment("plumbing", AiUrgency.low, 0.30, false, 1, 3);
        assertThat(engineWithDefaultRule().preliminaryEstimate(a).priceAvailable()).isFalse();
    }

    @Test
    void preliminaryEstimate_withholdsPriceOnEmergency() {
        AssessmentResult a = TestFixtures.assessment("plumbing", AiUrgency.emergency, 0.9, false, 1, 3);
        assertThat(engineWithDefaultRule().preliminaryEstimate(a).priceAvailable()).isFalse();
    }

    @Test
    void preliminaryEstimate_withholdsPriceOnRiskyCategory() {
        AssessmentResult a = TestFixtures.assessment("gas", AiUrgency.high, 0.9, false, 1, 3);
        assertThat(engineWithDefaultRule().preliminaryEstimate(a).priceAvailable()).isFalse();
    }
}
