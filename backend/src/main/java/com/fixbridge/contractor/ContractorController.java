package com.fixbridge.contractor;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.contractor.dto.ContractorDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/contractor")
@PreAuthorize("hasRole('contractor')")
public class ContractorController {

    private final ContractorService contractorService;

    public ContractorController(ContractorService contractorService) {
        this.contractorService = contractorService;
    }

    @PostMapping("/onboard")
    public ContractorDtos.ContractorView onboard(@Valid @RequestBody ContractorDtos.OnboardRequest req) {
        return contractorService.onboard(SecurityUtil.currentUser(), req);
    }

    @GetMapping("/invitations")
    public List<ContractorDtos.InvitationView> invitations() {
        return contractorService.myInvitations(SecurityUtil.currentUser());
    }

    @PostMapping("/jobs/{jobId}/bid")
    @ResponseStatus(HttpStatus.CREATED)
    public void submitBid(@PathVariable UUID jobId, @Valid @RequestBody ContractorDtos.BidRequest req) {
        contractorService.submitBid(SecurityUtil.currentUser(), jobId, req);
    }

    @PostMapping("/jobs/{jobId}/completion")
    public void submitCompletion(@PathVariable UUID jobId, @Valid @RequestBody ContractorDtos.CompletionRequest req) {
        contractorService.submitCompletion(SecurityUtil.currentUser(), jobId, req);
    }
}
