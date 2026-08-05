package com.fixbridge.proposal;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.payment.dto.PaymentDtos;
import com.fixbridge.proposal.dto.ProposalDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasRole('customer')")
public class ProposalController {

    private final ProposalService proposalService;

    public ProposalController(ProposalService proposalService) {
        this.proposalService = proposalService;
    }

    @GetMapping("/proposals")
    public List<ProposalDtos.CustomerProposalView> forJob(@RequestParam UUID jobId) {
        return proposalService.listForCustomer(SecurityUtil.currentUser(), jobId);
    }

    /** Approve the retail proposal → returns a Checkout URL to pay. */
    @PostMapping("/proposals/{proposalId}/approve")
    public PaymentDtos.CheckoutView approve(@PathVariable UUID proposalId) {
        return proposalService.approveAndCheckout(SecurityUtil.currentUser(), proposalId);
    }
}
