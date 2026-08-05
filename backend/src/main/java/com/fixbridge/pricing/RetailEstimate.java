package com.fixbridge.pricing;

import java.math.BigDecimal;

/**
 * Server-computed customer retail estimate. When {@code priceAvailable} is false the customer is shown
 * "On-site assessment required before pricing" (spec §9.8) and no numbers.
 */
public record RetailEstimate(
        boolean priceAvailable,
        Long retailLowCents,
        Long retailHighCents,
        BigDecimal confidence,
        String message,
        String disclaimer
) {
    public static RetailEstimate unavailable(BigDecimal confidence) {
        return new RetailEstimate(false, null, null, confidence,
                "On-site assessment required before pricing.",
                "A verified professional will confirm pricing after assessing on site.");
    }

    public static RetailEstimate range(long low, long high, BigDecimal confidence) {
        return new RetailEstimate(true, low, high, confidence,
                "Estimated service range.",
                "This is a preliminary estimate that includes coordination, technology, administration, "
                        + "payment handling, support and subcontracted service delivery. It is not a binding quote.");
    }
}
