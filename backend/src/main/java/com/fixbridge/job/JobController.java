package com.fixbridge.job;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.job.dto.DispatchQuoteDtos;
import com.fixbridge.job.dto.JobDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final DispatchQuoteService dispatchQuotes;

    public JobController(JobService jobService, DispatchQuoteService dispatchQuotes) {
        this.jobService = jobService;
        this.dispatchQuotes = dispatchQuotes;
    }

    /**
     * What this job will cost before anyone is dispatched: the FixBridge fee, which is zero during
     * beta, and the contractor's own visit fee, which is not.
     *
     * <p>Read-only. Seeing the quote commits the homeowner to nothing — acceptance is a separate
     * act, and dispatch a separate one after that.
     */
    @GetMapping("/{jobId}/dispatch-quote")
    @PreAuthorize("hasRole(\'customer\')")
    public DispatchQuoteDtos.DispatchQuote dispatchQuote(
            @PathVariable UUID jobId,
            @RequestParam(required = false) String trade,
            @RequestParam(required = false, defaultValue = "false") boolean emergency) {
        return dispatchQuotes.quoteFor(jobId, trade, emergency);
    }

    /** Report an Issue (homeowner wording). Runs AI assessment + server-side retail estimate. */
    @PostMapping
    @PreAuthorize("hasRole('customer')")
    public JobDtos.JobDetailView reportIssue(@Valid @RequestBody JobDtos.ReportIssueRequest req) {
        return jobService.reportIssue(SecurityUtil.currentUser(), req);
    }

    @GetMapping
    @PreAuthorize("hasRole('customer')")
    public List<JobDtos.JobSummaryView> myJobs() {
        return jobService.listForCustomer(SecurityUtil.currentUser());
    }

    @GetMapping("/{jobId}")
    public JobDtos.JobDetailView get(@PathVariable UUID jobId) {
        AuthUser user = SecurityUtil.currentUser();
        return jobService.getForCustomer(user, jobId);
    }

    /** The contractor's completion proof, for the customer to review before signing off. */
    @GetMapping("/{jobId}/completion")
    public JobDtos.CompletionView completion(@PathVariable UUID jobId) {
        return jobService.completionFor(SecurityUtil.currentUser(), jobId);
    }

    /** Customer signs off the completed work — payout stays blocked until this happens. */
    @PostMapping("/{jobId}/confirm-completion")
    public JobDtos.CompletionView confirmCompletion(@PathVariable UUID jobId) {
        return jobService.confirmCompletion(SecurityUtil.currentUser(), jobId);
    }
}
