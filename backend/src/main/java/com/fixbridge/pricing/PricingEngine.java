package com.fixbridge.pricing;

import com.fixbridge.ai.AssessmentResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * Server-side pricing engine. THE AI NEVER SETS PRICE — this class does, from admin {@link PricingRule}
 * data and the AI's technical signals only (spec §9.1–§9.5).
 *
 * <p>Retail = (net + fixedPlatformCost + riskReserve + fixedPaymentFee)
 *            / (1 − targetGrossMargin − variablePaymentFeeRate),
 * with a minimum-gross-profit floor for small jobs (the higher of the two wins).
 */
@Service
public class PricingEngine {

    /** Categories that must not be auto-priced — an on-site assessment is required first (spec §9.8). */
    private static final Set<String> NO_PRICE_CATEGORIES =
            Set.of("safety", "structural", "gas", "sewage", "electrical_major", "hazmat");

    private static final double MIN_CONFIDENCE_TO_PRICE = 0.5;

    // Pilot defaults for pre-bid net estimation (labor-hours → net). These are estimation heuristics
    // for the *preliminary* range only; the binding number always comes from the contractor's net bid.
    private static final long PILOT_HOURLY_RATE_CENTS = 9500;   // $95/hr expected contractor labor
    private static final long PILOT_MATERIALS_ALLOWANCE_CENTS = 6000; // $60 materials allowance

    private final PricingRuleRepository ruleRepository;

    public PricingEngine(PricingRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /** Preliminary customer retail range from an AI assessment (before any contractor bid). */
    public RetailEstimate preliminaryEstimate(AssessmentResult a) {
        BigDecimal confidence = a.confidence();
        boolean lowConfidence = confidence != null && confidence.doubleValue() < MIN_CONFIDENCE_TO_PRICE;
        boolean riskyCategory = a.category() != null && NO_PRICE_CATEGORIES.contains(a.category());
        boolean emergency = a.urgency() == com.fixbridge.common.enums.AiUrgency.emergency;

        if (lowConfidence || riskyCategory || emergency) {
            return RetailEstimate.unavailable(confidence);
        }

        PricingRule rule = globalRule();
        long netLow = estimateNetForHours(a.estimatedLaborHoursMin());
        long netHigh = estimateNetForHours(a.estimatedLaborHoursMax());
        long retailLow = retailForNet(netLow, rule);
        long retailHigh = retailForNet(netHigh, rule);
        return RetailEstimate.range(retailLow, retailHigh, confidence);
    }

    /** Final retail price for a known contractor net (used when an admin turns a bid into a proposal). */
    public long retailForNet(long contractorNetCents) {
        return retailForNet(contractorNetCents, globalRule());
    }

    long retailForNet(long contractorNetCents, PricingRule rule) {
        double margin = rule.getTargetGrossMargin().doubleValue();
        double variableFee = rule.getVariablePaymentFeeRate().doubleValue();
        double denominator = 1.0 - margin - variableFee;
        if (denominator <= 0) {
            throw new IllegalStateException("Invalid pricing rule: margin + fee rate >= 1");
        }
        double locationFactor = rule.getLocationFactor().doubleValue();

        long baseCosts = Math.round(contractorNetCents * locationFactor)
                + rule.getFixedPlatformCostCents()
                + rule.getRiskReserveCents()
                + rule.getFixedPaymentFeeCents();

        long marginBasedRetail = Math.round(baseCosts / denominator);

        // Minimum-gross-profit floor: retail must yield at least the minimum gross profit dollars.
        long minProfitRetail = Math.round(
                (contractorNetCents + rule.getFixedPlatformCostCents() + rule.getRiskReserveCents()
                        + rule.getMinimumGrossProfitCents() + rule.getFixedPaymentFeeCents())
                        / (1.0 - variableFee));

        return Math.max(marginBasedRetail, minProfitRetail);
    }

    /** Estimated contractor net for a labor-hour figure (internal / admin only, never shown to customer). */
    public long estimateNetForHours(BigDecimal laborHours) {
        double hours = laborHours == null ? 1.0 : laborHours.doubleValue();
        return Math.round(hours * PILOT_HOURLY_RATE_CENTS) + PILOT_MATERIALS_ALLOWANCE_CENTS;
    }

    private PricingRule globalRule() {
        return ruleRepository.findFirstByScopeAndActiveTrue("global")
                .orElseGet(PricingRule::new); // sensible defaults if none configured yet
    }

    /** Rounds cents to the nearest whole dollar for tidy customer-facing ranges. */
    public static long roundToDollar(long cents) {
        return BigDecimal.valueOf(cents)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .longValueExact();
    }
}
