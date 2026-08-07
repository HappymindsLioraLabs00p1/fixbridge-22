package com.fixbridge.admin.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReportDtos {

    private ReportDtos() {}

    /** Admin-only. Gross profit and margin are confidential — never surfaced to customers or contractors. */
    public record Overview(
            long collectedCents,
            long refundedCents,
            long netRevenueCents,
            long contractorPayoutsCents,
            long grossProfitCents,
            double grossMarginPercent,
            long jobsReported,
            long jobsCompleted,
            double conversionPercent,
            /** Stage → how many jobs currently sit there. */
            Map<String, Long> funnel,
            List<ContractorPerformance> contractors
    ) {}

    public record ContractorPerformance(
            UUID contractorId,
            String businessName,
            String status,
            long bidsSubmitted,
            long jobsPaidOut,
            long totalEarnedCents
    ) {}
}
