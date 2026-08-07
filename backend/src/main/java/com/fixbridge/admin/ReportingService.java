package com.fixbridge.admin;

import com.fixbridge.admin.dto.ReportDtos;
import com.fixbridge.common.enums.JobStatus;
import com.fixbridge.common.enums.PaymentStatus;
import com.fixbridge.common.enums.TransferStatus;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.job.BidRepository;
import com.fixbridge.job.Job;
import com.fixbridge.job.JobRepository;
import com.fixbridge.payment.Payment;
import com.fixbridge.payment.PaymentRepository;
import com.fixbridge.payment.Refund;
import com.fixbridge.payment.RefundRepository;
import com.fixbridge.payment.Transfer;
import com.fixbridge.payment.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin reporting (FR-ADMIN-6): revenue, gross profit, payouts and conversion. Every figure is
 * derived from recorded money rows — payments, refunds and transfers — never estimated.
 */
@Service
public class ReportingService {

    /** The stages worth showing as a funnel, in the order a job actually moves through them. */
    private static final List<JobStatus> FUNNEL = List.of(
            JobStatus.awaiting_service_payment,
            JobStatus.awaiting_contractor,
            JobStatus.bid_received,
            JobStatus.proposal_sent,
            JobStatus.scheduled,
            JobStatus.work_completed,
            JobStatus.paid_out);

    private final JobRepository jobs;
    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final TransferRepository transfers;
    private final ContractorRepository contractors;
    private final BidRepository bids;

    public ReportingService(JobRepository jobs, PaymentRepository payments, RefundRepository refunds,
                            TransferRepository transfers, ContractorRepository contractors, BidRepository bids) {
        this.jobs = jobs;
        this.payments = payments;
        this.refunds = refunds;
        this.transfers = transfers;
        this.contractors = contractors;
        this.bids = bids;
    }

    @Transactional(readOnly = true)
    public ReportDtos.Overview overview() {
        List<Payment> allPayments = payments.findAll();

        long collected = allPayments.stream()
                .filter(p -> p.getStatus() == PaymentStatus.succeeded || p.getStatus() == PaymentStatus.refunded)
                .mapToLong(Payment::getAmountCents).sum();
        long refunded = refunds.findAll().stream().mapToLong(Refund::getAmountCents).sum();
        long paidOut = transfers.findAll().stream()
                .filter(t -> t.getStatus() == TransferStatus.paid)
                .mapToLong(Transfer::getAmountCents).sum();

        long netRevenue = collected - refunded;
        long grossProfit = netRevenue - paidOut;

        List<Job> allJobs = jobs.findAll();
        long reported = allJobs.size();
        long completed = allJobs.stream()
                .filter(j -> j.getStatus() == JobStatus.paid_out || j.getStatus() == JobStatus.closed)
                .count();
        double conversion = reported == 0 ? 0 : (completed * 100.0) / reported;

        // Funnel — how many jobs currently sit at each meaningful stage.
        Map<String, Long> funnel = new LinkedHashMap<>();
        for (JobStatus s : FUNNEL) {
            funnel.put(s.name(), allJobs.stream().filter(j -> j.getStatus() == s).count());
        }

        return new ReportDtos.Overview(
                collected, refunded, netRevenue, paidOut, grossProfit,
                grossProfit == 0 || netRevenue == 0 ? 0 : (grossProfit * 100.0) / netRevenue,
                reported, completed, conversion, funnel, contractorPerformance());
    }

    /** Per-contractor delivery record — jobs paid out and what they earned. */
    private List<ReportDtos.ContractorPerformance> contractorPerformance() {
        List<Transfer> paid = transfers.findAll().stream()
                .filter(t -> t.getStatus() == TransferStatus.paid).toList();

        Map<UUID, long[]> byContractor = new LinkedHashMap<>(); // [jobCount, totalCents]
        for (Transfer t : paid) {
            long[] agg = byContractor.computeIfAbsent(t.getContractorId(), k -> new long[2]);
            agg[0]++;
            agg[1] += t.getAmountCents();
        }

        return contractors.findAll().stream()
                .map(c -> {
                    long[] agg = byContractor.getOrDefault(c.getId(), new long[2]);
                    long bidCount = bids.findAll().stream()
                            .filter(b -> b.getContractorId().equals(c.getId())).count();
                    return new ReportDtos.ContractorPerformance(
                            c.getId(), c.getBusinessName(), c.getStatus().name(),
                            bidCount, agg[0], agg[1]);
                })
                .sorted((a, b) -> Long.compare(b.totalEarnedCents(), a.totalEarnedCents()))
                .toList();
    }
}
