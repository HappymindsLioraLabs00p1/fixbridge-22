package com.fixbridge.admin;

import com.fixbridge.admin.dto.AdminDtos;
import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.payment.dto.PaymentDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/dispatch-queue")
    public List<AdminDtos.AdminJobView> dispatchQueue() {
        return adminService.dispatchQueue();
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
}
