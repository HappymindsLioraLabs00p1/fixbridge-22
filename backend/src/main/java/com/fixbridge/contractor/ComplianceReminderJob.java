package com.fixbridge.contractor;

import com.fixbridge.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Warns contractors before required paperwork lapses — 30, 14 and 7 days out (FR-CON-3) — and marks
 * documents expired once the date passes, which removes the contractor from dispatch automatically.
 */
@Component
public class ComplianceReminderJob {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReminderJob.class);
    private static final List<Integer> REMIND_DAYS = List.of(30, 14, 7);

    private final ContractorDocumentRepository documents;
    private final ContractorRepository contractors;
    private final NotificationService notifications;

    public ComplianceReminderJob(ContractorDocumentRepository documents, ContractorRepository contractors,
                                 NotificationService notifications) {
        this.documents = documents;
        this.contractors = contractors;
        this.notifications = notifications;
    }

    /** Runs daily at 08:00 server time. */
    @Scheduled(cron = "0 0 8 * * *")
    @Transactional
    public void run() {
        int reminded = 0;
        for (int days : REMIND_DAYS) {
            LocalDate target = LocalDate.now().plusDays(days);
            for (ContractorDocument doc : documents.findValidExpiringOn(target)) {
                contractors.findById(doc.getContractorId()).ifPresent(c ->
                        notifications.complianceExpiring(c.getOwnerUserId(), doc.getKind(), doc.getExpiresOn()));
                reminded++;
            }
        }

        // Anything already past its date is no longer valid — this is what pulls a lapsed contractor
        // out of the dispatch pool without anyone having to notice.
        int expired = 0;
        for (ContractorDocument doc : documents.findAll()) {
            if (doc.isExpired() && doc.getStatus() == com.fixbridge.common.enums.DocumentStatus.valid) {
                doc.setStatus(com.fixbridge.common.enums.DocumentStatus.expired);
                documents.save(doc);
                contractors.findById(doc.getContractorId()).ifPresent(c -> {
                    if (c.getStatus() == com.fixbridge.common.enums.ContractorStatus.approved) {
                        c.setStatus(com.fixbridge.common.enums.ContractorStatus.expired);
                        contractors.save(c);
                    }
                });
                expired++;
            }
        }
        if (reminded > 0 || expired > 0) {
            log.info("Compliance sweep: {} reminders sent, {} documents marked expired", reminded, expired);
        }
    }
}
