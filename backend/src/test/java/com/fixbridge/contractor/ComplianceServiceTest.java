package com.fixbridge.contractor;

import com.fixbridge.audit.AuditService;
import com.fixbridge.common.enums.DocumentStatus;
import com.fixbridge.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComplianceServiceTest {

    private final UUID contractorId = UUID.randomUUID();

    private ComplianceService serviceWith(List<ContractorDocument> docs) {
        ContractorDocumentRepository repo = mock(ContractorDocumentRepository.class);
        when(repo.findByContractorIdOrderByCreatedAtDesc(contractorId)).thenReturn(docs);
        return new ComplianceService(repo, mock(ContractorRepository.class), mock(StorageService.class),
                mock(AuditService.class));
    }

    private ContractorDocument doc(String kind, DocumentStatus status, LocalDate expiresOn) {
        ContractorDocument d = new ContractorDocument();
        d.setContractorId(contractorId);
        d.setKind(kind);
        d.setStatus(status);
        d.setExpiresOn(expiresOn);
        return d;
    }

    @Test
    void compliantWhenLicenceAndInsuranceAreVerifiedAndCurrent() {
        var service = serviceWith(List.of(
                doc(ContractorDocument.LICENSE, DocumentStatus.valid, LocalDate.now().plusMonths(6)),
                doc(ContractorDocument.INSURANCE, DocumentStatus.valid, LocalDate.now().plusMonths(3))));
        assertThat(service.isCompliant(contractorId)).isTrue();
    }

    @Test
    void notCompliantWhenInsuranceIsMissing() {
        var service = serviceWith(List.of(
                doc(ContractorDocument.LICENSE, DocumentStatus.valid, LocalDate.now().plusMonths(6))));
        assertThat(service.isCompliant(contractorId)).isFalse();
        assertThat(service.statusFor(contractorId).missingOrUnverified())
                .containsExactly(ContractorDocument.INSURANCE);
    }

    @Test
    void expiredInsuranceBlocksDispatchEvenThoughItWasVerified() {
        var service = serviceWith(List.of(
                doc(ContractorDocument.LICENSE, DocumentStatus.valid, LocalDate.now().plusMonths(6)),
                // Verified once, but the date has passed — must not count.
                doc(ContractorDocument.INSURANCE, DocumentStatus.valid, LocalDate.now().minusDays(1))));
        assertThat(service.isCompliant(contractorId)).isFalse();
        assertThat(service.statusFor(contractorId).expired()).containsExactly(ContractorDocument.INSURANCE);
    }

    @Test
    void pendingDocumentDoesNotCountAsCompliant() {
        var service = serviceWith(List.of(
                doc(ContractorDocument.LICENSE, DocumentStatus.valid, LocalDate.now().plusYears(1)),
                doc(ContractorDocument.INSURANCE, DocumentStatus.pending, LocalDate.now().plusYears(1))));
        assertThat(service.isCompliant(contractorId)).isFalse();
    }

    @Test
    void documentWithoutAnExpiryNeverLapses() {
        var service = serviceWith(List.of(
                doc(ContractorDocument.LICENSE, DocumentStatus.valid, null),
                doc(ContractorDocument.INSURANCE, DocumentStatus.valid, null)));
        assertThat(service.isCompliant(contractorId)).isTrue();
    }
}
