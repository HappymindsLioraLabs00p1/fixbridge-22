package com.fixbridge.contractor;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.ContractorStatus;
import com.fixbridge.common.enums.DocumentStatus;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.contractor.dto.ComplianceDtos;
import com.fixbridge.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contractor compliance (FR-CON-1/2/3). A contractor must hold a current licence and insurance
 * before they can be dispatched; expired paperwork automatically removes them from dispatch.
 */
@Service
public class ComplianceService {

    /** Without these, a contractor cannot be sent to a customer's property. */
    public static final Set<String> REQUIRED_KINDS =
            Set.of(ContractorDocument.LICENSE, ContractorDocument.INSURANCE);

    private final ContractorDocumentRepository documents;
    private final ContractorRepository contractors;
    private final StorageService storage;
    private final com.fixbridge.audit.AuditService audit;

    public ComplianceService(ContractorDocumentRepository documents, ContractorRepository contractors,
                             StorageService storage, com.fixbridge.audit.AuditService audit) {
        this.documents = documents;
        this.contractors = contractors;
        this.storage = storage;
        this.audit = audit;
    }

    /** Contractor submits (or replaces) a compliance document; it awaits admin review. */
    @Transactional
    public ComplianceDtos.DocumentView submit(UUID contractorId, ComplianceDtos.SubmitDocumentRequest req) {
        if (!isKnownKind(req.kind())) {
            throw ApiException.badRequest("Unknown document type");
        }
        ContractorDocument doc = new ContractorDocument();
        doc.setContractorId(contractorId);
        doc.setKind(req.kind());
        doc.setJurisdiction(req.jurisdiction());
        doc.setNumber(req.number());
        doc.setStorageKey(req.storageKey());
        doc.setExpiresOn(req.expiresOn());
        doc.setStatus(DocumentStatus.pending);
        documents.save(doc);

        // Submitting paperwork moves a draft contractor into the review pipeline (FR-CON-2).
        contractors.findById(contractorId).ifPresent(c -> {
            if (c.getStatus() == ContractorStatus.draft || c.getStatus() == ContractorStatus.documents_pending) {
                c.setStatus(hasAllRequiredSubmitted(contractorId)
                        ? ContractorStatus.under_review
                        : ContractorStatus.documents_pending);
                contractors.save(c);
            }
        });
        return toView(doc);
    }

    @Transactional(readOnly = true)
    public List<ComplianceDtos.DocumentView> listFor(UUID contractorId) {
        return documents.findByContractorIdOrderByCreatedAtDesc(contractorId).stream().map(this::toView).toList();
    }

    /** Admin verifies or rejects a document, then the contractor's overall status is recomputed. */
    @Transactional
    public ComplianceDtos.DocumentView review(AuthUser admin, UUID documentId, boolean approve, String note) {
        ContractorDocument doc = documents.findById(documentId)
                .orElseThrow(() -> ApiException.notFound("Document"));
        doc.setStatus(approve ? DocumentStatus.valid : DocumentStatus.rejected);
        documents.save(doc);

        recomputeContractorStatus(doc.getContractorId());
        audit.record(admin.id(), approve ? "compliance.approve" : "compliance.reject",
                "contractor_document", documentId,
                java.util.Map.of("contractorId", doc.getContractorId().toString(),
                        "kind", doc.getKind(), "note", note == null ? "" : note));
        return toView(doc);
    }

    /** Admin suspends or reinstates a contractor (FR-CON-3, FR-ADMIN-5). */
    @Transactional
    public void setSuspended(AuthUser admin, UUID contractorId, boolean suspended, String reason) {
        Contractor c = contractors.findById(contractorId)
                .orElseThrow(() -> ApiException.notFound("Contractor"));
        c.setStatus(suspended ? ContractorStatus.suspended : ContractorStatus.under_review);
        contractors.save(c);
        if (!suspended) recomputeContractorStatus(contractorId);
        audit.record(admin.id(), suspended ? "contractor.suspend" : "contractor.reinstate",
                "contractor", contractorId, java.util.Map.of("reason", reason == null ? "" : reason));
    }

    /**
     * Compliance summary used both by the contractor's own dashboard and by dispatch eligibility.
     * A contractor is compliant only when every required document is verified and unexpired.
     */
    @Transactional(readOnly = true)
    public ComplianceDtos.ComplianceStatus statusFor(UUID contractorId) {
        List<ContractorDocument> docs = documents.findByContractorIdOrderByCreatedAtDesc(contractorId);
        List<String> missing = REQUIRED_KINDS.stream()
                .filter(kind -> docs.stream().noneMatch(d -> d.getKind().equals(kind) && d.isCurrentlyValid()))
                .sorted()
                .toList();
        List<String> expired = docs.stream()
                .filter(d -> REQUIRED_KINDS.contains(d.getKind()) && d.isExpired())
                .map(ContractorDocument::getKind).distinct().sorted().toList();
        List<ComplianceDtos.DocumentView> views = docs.stream().map(this::toView).toList();
        return new ComplianceDtos.ComplianceStatus(missing.isEmpty(), missing, expired, views);
    }

    /** True when every required document is verified and current — the dispatch gate. */
    @Transactional(readOnly = true)
    public boolean isCompliant(UUID contractorId) {
        List<ContractorDocument> docs = documents.findByContractorIdOrderByCreatedAtDesc(contractorId);
        return REQUIRED_KINDS.stream()
                .allMatch(kind -> docs.stream().anyMatch(d -> d.getKind().equals(kind) && d.isCurrentlyValid()));
    }

    // ---- internals ----

    private void recomputeContractorStatus(UUID contractorId) {
        contractors.findById(contractorId).ifPresent(c -> {
            if (c.getStatus() == ContractorStatus.suspended || c.getStatus() == ContractorStatus.rejected) {
                return; // an admin decision outranks automatic recomputation
            }
            if (isCompliant(contractorId)) {
                c.setStatus(ContractorStatus.approved);
            } else if (hasAnyExpiredRequired(contractorId)) {
                c.setStatus(ContractorStatus.expired);
            } else if (hasAllRequiredSubmitted(contractorId)) {
                c.setStatus(ContractorStatus.under_review);
            } else {
                c.setStatus(ContractorStatus.documents_pending);
            }
            contractors.save(c);
        });
    }

    private boolean hasAllRequiredSubmitted(UUID contractorId) {
        List<ContractorDocument> docs = documents.findByContractorIdOrderByCreatedAtDesc(contractorId);
        return REQUIRED_KINDS.stream()
                .allMatch(kind -> docs.stream().anyMatch(d -> d.getKind().equals(kind)
                        && d.getStatus() != DocumentStatus.rejected));
    }

    private boolean hasAnyExpiredRequired(UUID contractorId) {
        return documents.findByContractorIdOrderByCreatedAtDesc(contractorId).stream()
                .anyMatch(d -> REQUIRED_KINDS.contains(d.getKind()) && d.isExpired());
    }

    private boolean isKnownKind(String kind) {
        return List.of(ContractorDocument.LICENSE, ContractorDocument.INSURANCE,
                ContractorDocument.WORKERS_COMP, ContractorDocument.W9).contains(kind);
    }

    private ComplianceDtos.DocumentView toView(ContractorDocument d) {
        String url = d.getStorageKey() == null ? null : storage.createDownloadUrl(d.getStorageKey());
        String effective = d.isExpired() ? "expired" : d.getStatus().name();
        return new ComplianceDtos.DocumentView(d.getId(), d.getKind(), d.getJurisdiction(), d.getNumber(),
                effective, d.getExpiresOn(), d.daysUntilExpiry(), url);
    }
}
