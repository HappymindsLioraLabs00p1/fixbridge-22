package com.fixbridge.assistant.tools;

import com.fixbridge.assistant.AssistantTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.JobService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * "What's happening with job X?" — the full detail of one job.
 *
 * <p>This tool does take an id, which makes it the one place in the read-only set where the model
 * supplies something that selects a row. It is therefore routed through
 * {@link JobService#getForCustomer} rather than a repository lookup: that method enforces ownership,
 * so a job id the model invented, or lifted from an earlier conversation with a different customer,
 * returns a not-found rather than another person's repair history.
 */
@Component
public class JobStatusTool implements AssistantTool {

    private final JobService jobs;

    public JobStatusTool(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "get_job_status";
    }

    @Override
    public String description() {
        return "Get the full detail and current status of one of the customer's jobs, by job id.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of("job_id", Map.of(
                        "type", "string",
                        "description", "The id of the job, as shown by list_my_jobs")),
                "required", java.util.List.of("job_id"));
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public Object execute(AuthUser user, Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("job_id");
        if (raw == null || raw.toString().isBlank()) {
            throw ApiException.badRequest("Which job did you mean? I need the job reference.");
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException e) {
            // A model will cheerfully pass "the kitchen one". Fail as a bad request rather than a 500.
            throw ApiException.badRequest("That does not look like a job reference.");
        }
        return jobs.getForCustomer(user, jobId);
    }
}
