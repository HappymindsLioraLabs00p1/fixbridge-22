package com.fixbridge.job.dto;

import com.fixbridge.common.enums.AiUrgency;
import com.fixbridge.common.enums.JobStatus;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Job request/response payloads. Customer-facing views never include contractor net or margin. */
public final class JobDtos {

    private JobDtos() {}

    public record ReportIssueRequest(
            @NotNull UUID propertyId,
            String title,
            String description,
            List<String> mediaKeys,
            String preferredTime,
            String partnerCode
    ) {}

    /** Customer-safe assessment view (no pricing internals). */
    public record AssessmentView(
            String category,
            String summary,
            AiUrgency urgency,
            BigDecimal confidence,
            String recommendedTrade,
            boolean professionalRequired,
            boolean safeDiyAllowed,
            List<String> immediateSafetySteps,
            String disclaimer
    ) {}

    /** Customer-facing retail estimate (range or "assessment required"). Never the contractor net. */
    public record EstimateView(
            boolean priceAvailable,
            Long retailLowCents,
            Long retailHighCents,
            String message,
            String disclaimer
    ) {}

    public record JobDetailView(
            UUID id,
            JobStatus status,
            String title,
            String description,
            String preferredTime,
            AssessmentView assessment,
            EstimateView estimate,
            Instant createdAt
    ) {}

    public record JobSummaryView(UUID id, JobStatus status, String title, Instant createdAt) {}
}
