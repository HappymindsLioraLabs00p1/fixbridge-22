package com.fixbridge.admin;

import com.fixbridge.admin.dto.AdminDtos;
import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.job.ChangeOrderService;
import com.fixbridge.job.dto.ChangeOrderDtos;
import com.fixbridge.payment.dto.PaymentDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('admin')")
public class AdminController {

    private final AdminService adminService;
    private final ChangeOrderService changeOrderService;
    private final com.fixbridge.payment.PaymentService paymentService;

    public AdminController(AdminService adminService, ChangeOrderService changeOrderService,
                           com.fixbridge.payment.PaymentService paymentService) {
        this.adminService = adminService;
        this.changeOrderService = changeOrderService;
        this.paymentService = paymentService;
    }

    @GetMapping("/dispatch-queue")
    public List<AdminDtos.AdminJobView> dispatchQueue() {
        return adminService.dispatchQueue();
    }

    /** Pick-list of contractors to invite, with eligibility. */
    @GetMapping("/contractors")
    public List<AdminDtos.ContractorOption> contractors() {
        return adminService.contractorOptions();
    }

    /** Pick-list of bids submitted for a job, each previewing retail + margin. */
    @GetMapping("/jobs/{jobId}/bids")
    public List<AdminDtos.BidOption> bids(@PathVariable UUID jobId) {
        return adminService.bidOptions(jobId);
    }

    @PostMapping("/jobs/{jobId}/invite")
    public void invite(@PathVariable UUID jobId, @Valid @RequestBody AdminDtos.InviteRequest req) {
        adminService.inviteContractor(SecurityUtil.currentUser(), jobId, req.contractorId());
    }

    @PostMapping("/jobs/{jobId}/proposal")
    public AdminDtos.AdminProposalView createProposal(@PathVariable UUID jobId,
                                                      @Valid @RequestBody AdminDtos.CreateProposalRequest req) {
        return adminService.createProposal(SecurityUtil.currentUser(), jobId, req);
    }

    @PostMapping("/jobs/{jobId}/payout")
    public PaymentDtos.PayoutView releasePayout(@PathVariable UUID jobId) {
        return adminService.releasePayout(SecurityUtil.currentUser(), jobId);
    }

    // ---- Money controls: refunds, disputes, payout holds (FR-PAY-9, FR-ADMIN-4) ----

    /** Payments taken for a job, with refunded/refundable amounts and dispute flags. */
    @GetMapping("/jobs/{jobId}/payments")
    public List<PaymentDtos.PaymentView> payments(@PathVariable UUID jobId) {
        return paymentService.paymentsForJob(jobId);
    }

    /** Refund a payment, full or partial. The refundable ceiling is enforced server-side. */
    @PostMapping("/payments/{paymentId}/refund")
    public PaymentDtos.RefundView refund(@PathVariable UUID paymentId,
                                         @Valid @RequestBody PaymentDtos.RefundRequest req) {
        return paymentService.refund(SecurityUtil.currentUser(), paymentId, req.amountCents(), req.reason());
    }

    /** Place a hold on a job's contractor payout. */
    @PostMapping("/jobs/{jobId}/payout-hold")
    public PaymentDtos.PayoutHoldView holdPayout(@PathVariable UUID jobId,
                                                 @Valid @RequestBody PaymentDtos.HoldRequest req) {
        return paymentService.setPayoutHold(SecurityUtil.currentUser(), jobId, req.reason());
    }

    /** Lift an existing payout hold. */
    @DeleteMapping("/jobs/{jobId}/payout-hold")
    public PaymentDtos.PayoutHoldView releaseHold(@PathVariable UUID jobId) {
        return paymentService.setPayoutHold(SecurityUtil.currentUser(), jobId, null);
    }

    /** Review change orders for a job (admin sees added net, retail and margin). */
    @GetMapping("/jobs/{jobId}/change-orders")
    public List<ChangeOrderDtos.AdminView> changeOrders(@PathVariable UUID jobId) {
        return changeOrderService.listForAdmin(jobId);
    }

    /** Apply retail pricing rules to a change order and send it to the customer for approval. */
    @PostMapping("/change-orders/{changeOrderId}/publish")
    public ChangeOrderDtos.AdminView publishChangeOrder(@PathVariable UUID changeOrderId) {
        return changeOrderService.publish(SecurityUtil.currentUser(), changeOrderId);
    }
}
