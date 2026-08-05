package com.fixbridge.job;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.job.dto.JobDtos;
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
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
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
}
