package com.fixbridge.assistant.tools;

import com.fixbridge.assistant.AssistantTool;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.job.JobService;
import com.fixbridge.job.dto.JobDtos;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * "My kitchen tap is leaking" — files a repair job.
 *
 * <p>The first tool that writes anything, so it is the first that requires the customer's explicit
 * confirmation. It creates the job in {@code draft}: an assessment runs and a price range is
 * calculated, but nothing is dispatched and no money moves until the customer acts on it separately.
 *
 * <p>Two fields the model is deliberately <em>not</em> allowed to supply:
 *
 * <ul>
 *   <li>{@code partnerCode} — carries commercial terms. A model that hallucinated one would apply a
 *       discount nobody authorised, so it is always null here.
 *   <li>{@code mediaKeys} — storage object keys. Accepting these from the model would let an invented
 *       key attach a stranger's uploaded photograph to this job.
 * </ul>
 *
 * <p>Ownership of the property is enforced by {@link JobService#reportIssue}, which rejects a property
 * belonging to somebody else. The check is repeated here only to phrase the confirmation prompt, never
 * as the security boundary.
 */
@Component
public class ReportIssueTool implements AssistantTool {

    private final JobService jobs;
    private final PropertyRepository properties;

    public ReportIssueTool(JobService jobs, PropertyRepository properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "report_issue";
    }

    @Override
    public String description() {
        return "File a new repair job for one of the customer's properties. Requires the customer's "
                + "confirmation. Call list_my_properties first to get a property_id.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "property_id", Map.of("type", "string",
                                "description", "Property to report against, from list_my_properties"),
                        "title", Map.of("type", "string",
                                "description", "Short summary, e.g. 'Kitchen tap leaking'"),
                        "description", Map.of("type", "string",
                                "description", "What the customer described, in their own words")),
                "required", List.of("property_id", "title"));
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public String confirmationPrompt(AuthUser user, Map<String, Object> arguments) {
        String title = text(arguments, "title");
        // Tolerant on purpose: this runs before execute() has validated anything, and a prompt that
        // threw would turn "let me check that with you" into an error the customer never asked for.
        String where = Optional.ofNullable(parsePropertyIdOrNull(arguments))
                .flatMap(properties::findById)
                .filter(p -> p.getOwnerId().equals(user.id()))
                .map(ListMyPropertiesTool::describe)
                .orElse("your property");
        return "Shall I report \"" + (title.isBlank() ? "this issue" : title) + "\" at " + where + "?";
    }

    @Override
    public Object execute(AuthUser user, Map<String, Object> arguments) {
        UUID propertyId = parsePropertyId(arguments);
        String title = text(arguments, "title");
        if (title.isBlank()) {
            throw ApiException.badRequest("I need a short summary of the problem.");
        }
        String description = text(arguments, "description");
        return jobs.reportIssue(user, new JobDtos.ReportIssueRequest(
                propertyId,
                title,
                description.isBlank() ? title : description,
                List.of(),   // media never comes from the model — see the class comment
                null,
                null));      // partner code likewise
    }

    /** Strict: used on the write path, where a bad id must stop the call. */
    private static UUID parsePropertyId(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("property_id");
        if (raw == null || raw.toString().isBlank()) {
            throw ApiException.badRequest("Which property is this for?");
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("That does not look like one of your properties.");
        }
    }

    /** Tolerant: used on the prompt path, which must produce a sentence whatever the model sent. */
    private static UUID parsePropertyIdOrNull(Map<String, Object> arguments) {
        Object raw = arguments == null ? null : arguments.get("property_id");
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.toString().trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object raw = arguments == null ? null : arguments.get(key);
        return raw == null ? "" : raw.toString().trim();
    }
}
