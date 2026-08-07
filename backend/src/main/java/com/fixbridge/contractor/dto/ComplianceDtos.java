package com.fixbridge.contractor.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ComplianceDtos {

    private ComplianceDtos() {}

    public record SubmitDocumentRequest(
            @NotBlank String kind,
            String jurisdiction,
            String number,
            /** Object key from the media upload flow — the file itself never passes through this API. */
            String storageKey,
            LocalDate expiresOn
    ) {}

    public record DocumentView(
            UUID id,
            String kind,
            String jurisdiction,
            String number,
            /** pending | valid | rejected | expired — "expired" wins over the stored status. */
            String status,
            LocalDate expiresOn,
            Long daysUntilExpiry,
            String fileUrl
    ) {}

    /** Whether this contractor may be dispatched, and what's blocking it if not. */
    public record ComplianceStatus(
            boolean compliant,
            List<String> missingOrUnverified,
            List<String> expired,
            List<DocumentView> documents
    ) {}

    public record ReviewRequest(boolean approve, String note) {}

    public record SuspendRequest(boolean suspended, String reason) {}
}
