package com.fixbridge.assistant.tools;

import com.fixbridge.assistant.AssistantTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.job.JobService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * "What jobs do I have?" — the customer's own jobs, newest first.
 *
 * <p>Takes no arguments by design. The caller's identity comes from the session, never from the
 * model, so there is no id here for a hallucinated value to land in.
 */
@Component
public class ListMyJobsTool implements AssistantTool {

    private final JobService jobs;

    public ListMyJobsTool(JobService jobs) {
        this.jobs = jobs;
    }

    @Override
    public String name() {
        return "list_my_jobs";
    }

    @Override
    public String description() {
        return "List the customer's own repair jobs with their current status. Takes no arguments.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public boolean mutating() {
        return false;
    }

    @Override
    public Object execute(AuthUser user, Map<String, Object> arguments) {
        return jobs.listForCustomer(user);
    }
}
