package com.fixbridge.admin;

import com.fixbridge.contractor.ContractorDocumentRepository;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.ChangeOrderRepository;
import com.fixbridge.job.CompletionReportRepository;
import com.fixbridge.job.JobRepository;
import com.fixbridge.proposal.ProposalRepository;
import com.fixbridge.payment.PaymentRepository;
import com.fixbridge.payment.RefundRepository;
import com.fixbridge.payment.TransferRepository;
import com.fixbridge.property.PropertyRepository;
import com.fixbridge.user.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exports every business record as JSON so a backup can be taken without database credentials.
 *
 * <p>This is deliberately not a substitute for {@code pg_dump} — it captures data, not schema — but
 * it is the backup you can actually take: from a browser, over the API, with nothing but an admin
 * session. Flyway rebuilds the schema on any empty database, so this file plus the repository is
 * enough to reconstruct the system.
 *
 * <p>Password hashes are excluded. A stolen export should not hand anyone an offline cracking
 * target, and hashes are not something you would want to restore anyway.
 */
@Service
public class DataExportService {

    private final ProfileRepository profiles;
    private final PropertyRepository properties;
    private final JobRepository jobs;
    private final BidRepository bids;
    private final ProposalRepository proposals;
    private final ContractorRepository contractors;
    private final ContractorDocumentRepository contractorDocuments;
    private final PaymentRepository payments;
    private final TransferRepository transfers;
    private final RefundRepository refunds;
    private final ChangeOrderRepository changeOrders;
    private final CompletionReportRepository completionReports;

    public DataExportService(ProfileRepository profiles, PropertyRepository properties, JobRepository jobs,
                             BidRepository bids, ProposalRepository proposals, ContractorRepository contractors,
                             ContractorDocumentRepository contractorDocuments, PaymentRepository payments,
                             TransferRepository transfers, RefundRepository refunds,
                             ChangeOrderRepository changeOrders, CompletionReportRepository completionReports) {
        this.profiles = profiles;
        this.properties = properties;
        this.jobs = jobs;
        this.bids = bids;
        this.proposals = proposals;
        this.contractors = contractors;
        this.contractorDocuments = contractorDocuments;
        this.payments = payments;
        this.transfers = transfers;
        this.refunds = refunds;
        this.changeOrders = changeOrders;
        this.completionReports = completionReports;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> exportAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now().toString());
        out.put("format", "fixbridge-export-v1");
        out.put("note", "Business data only — no password hashes. Flyway rebuilds the schema.");

        // Accounts, with credentials stripped.
        out.put("profiles", profiles.findAll().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("email", p.getEmail());
            m.put("fullName", p.getFullName());
            m.put("phone", p.getPhone());
            m.put("emailVerified", p.isEmailVerified());
            m.put("createdAt", p.getCreatedAt());
            return m;
        }).toList());

        out.put("properties", properties.findAll());
        out.put("jobs", jobs.findAll());
        out.put("bids", bids.findAll());
        out.put("proposals", proposals.findAll());
        out.put("contractors", contractors.findAll());
        out.put("contractorDocuments", contractorDocuments.findAll());
        out.put("payments", payments.findAll());
        out.put("transfers", transfers.findAll());
        out.put("refunds", refunds.findAll());
        out.put("changeOrders", changeOrders.findAll());
        out.put("completionReports", completionReports.findAll());

        Map<String, Integer> counts = new LinkedHashMap<>();
        out.forEach((k, v) -> {
            if (v instanceof java.util.Collection<?> c) counts.put(k, c.size());
        });
        out.put("recordCounts", counts);
        return out;
    }
}
