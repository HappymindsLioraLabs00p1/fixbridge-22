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

    /** A stored photo/video with a short-lived signed view URL. */
    public record MediaView(String mediaType, String url) {}

    /** The contractor's proof of completion, as shown to the customer for sign-off (FR-JOB-7/8). */
    public record CompletionView(
            UUID id,
            String summary,
            String materialsUsed,
            Instant arrivedAt,
            Instant completedAt,
            List<String> beforePhotoUrls,
            List<String> afterPhotoUrls,
            String invoiceUrl,
            String warrantyText,
            boolean approved,
            Instant approvedAt
    ) {}

    public record JobDetailView(
            UUID id,
            JobStatus status,
            String title,
            String description,
            String preferredTime,
            AssessmentView assessment,
            EstimateView estimate,
            List<MediaView> media,
            Instant createdAt,
            /**
             * Null until a contractor is actually assigned — which is most of a job's life. Appended
             * last and nullable so existing clients that ignore it are unaffected.
             */
            AssignedProfessionalView professional
    ) {}

    /**
     * The professional assigned to a job, as the customer may see them.
     *
     * <p>Deliberately not the contractor record. A customer needs to know who is coming and that
     * they are vetted; they have no business with the contractor's net bid, their email, their
     * payout account or their address, all of which sit on {@link com.fixbridge.contractor.Contractor}.
     *
     * <p>{@code maskedPhone} is masked <em>here</em>, on the server, rather than in the browser.
     * Masking in the UI would still send the full number over the wire and put it in every browser
     * cache and network log — the number would be hidden from the reader, not from the client. Only
     * the last four digits ever leave the server.
     *
     * <p>{@code rating} is null rather than zero for a professional nobody has reviewed yet: no
     * history is not the same as bad history.
     */
    public record AssignedProfessionalView(
            UUID contractorId,
            String businessName,
            String maskedPhone,
            boolean verified,
            Double rating,
            long reviewCount
    ) {}

    public record JobSummaryView(UUID id, JobStatus status, String title, Instant createdAt) {}
}
