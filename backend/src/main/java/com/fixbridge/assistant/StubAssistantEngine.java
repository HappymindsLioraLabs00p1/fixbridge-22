package com.fixbridge.assistant;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.catalog.dto.CatalogDtos;
import com.fixbridge.job.dto.JobDtos;
import com.fixbridge.property.Property;
import com.fixbridge.property.PropertyRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The assistant when no language model is configured.
 *
 * <p>This is not a placeholder. {@code AI_STUB_MODE} defaults to true and is true in production today,
 * so for the moment this is the assistant customers actually meet — it therefore has to give real
 * answers from real data rather than canned text. It routes by keyword to the same tools a model would
 * choose, and every answer it gives is read out of the database.
 *
 * <p>What it cannot do is hold a conversation: there is no memory between turns and no understanding
 * beyond word matching. Where a model would ask a follow-up, this engine either resolves the ambiguity
 * itself or says plainly that it needs the customer to be more specific.
 *
 * <p>It reads {@link PropertyRepository} directly, which tools otherwise exist to avoid. That is a
 * deliberate stub-tier shortcut: a model would spend a turn calling {@code list_my_properties} and
 * remember the answer, and this engine has no turns to spend. The read is owner-scoped, so it cannot
 * see further than the equivalent tool would.
 */
@Component
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "true", matchIfMissing = true)
public class StubAssistantEngine implements AssistantEngine {

    private final PropertyRepository properties;

    public StubAssistantEngine(PropertyRepository properties) {
        this.properties = properties;
    }

    private static final List<String> PROBLEM_WORDS = List.of(
            "leak", "leaking", "broken", "break", "blocked", "clog", "not working", "won't",
            "wont", "damaged", "crack", "no hot water", "no power", "flood", "burst", "stuck",
            "dripping", "faulty", "repair", "fix", "damp", "mould", "mold", "noisy");

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public Decision decide(AuthUser user, String message) {
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();

        if (m.isBlank()) {
            return Decision.say("What can I help you with?");
        }
        if (m.matches("^(hi|hello|hey|good (morning|afternoon|evening))\\b.*")) {
            return Decision.say("Hello — tell me what needs fixing, or ask about a job you already have.");
        }
        // Order matters: "how much to fix a leak" is a price question, not a repair report, so the
        // money words are tested before the problem words.
        if (containsAny(m, "how much", "price", "cost", "quote", "estimate", "charge", "fee")) {
            return Decision.use("list_services", Map.of());
        }
        if (containsAny(m, "my job", "my jobs", "status", "booking", "progress", "what's happening",
                "whats happening", "update")) {
            return Decision.use("list_my_jobs", Map.of());
        }
        if (containsAny(m, "propert", "address", "my home", "my house")) {
            return Decision.use("list_my_properties", Map.of());
        }
        if (containsAny(m, "what do you", "services", "trades", "cover")) {
            return Decision.use("list_services", Map.of());
        }
        if (containsAny(m, PROBLEM_WORDS.toArray(new String[0]))) {
            return reportIssue(user, message.trim());
        }
        return Decision.say("I can look up your jobs, list what we fix and what it typically costs, "
                + "or report a new problem. Try describing what has gone wrong.");
    }

    /**
     * A described problem becomes a report — but only when there is no doubt about where. Guessing the
     * address would file a repair against the wrong home, so more than one property is a question, not
     * a coin toss.
     */
    private Decision reportIssue(AuthUser user, String original) {
        List<Property> owned = properties.findByOwnerId(user.id());
        if (owned.isEmpty()) {
            return Decision.say("Add a property first and I can report that against it.");
        }
        if (owned.size() > 1) {
            String list = owned.stream().map(StubAssistantEngine::label).reduce((a, b) -> a + ", " + b).orElse("");
            return Decision.say("Which property is that for — " + list + "?");
        }
        return Decision.use("report_issue", Map.of(
                "property_id", owned.get(0).getId().toString(),
                "title", summarise(original),
                "description", original));
    }

    @Override
    public String narrate(String toolName, Object result) {
        return switch (toolName) {
            case "list_my_jobs" -> narrateJobs(result);
            case "list_services" -> narrateServices(result);
            case "list_my_properties" -> narrateProperties(result);
            case "report_issue" -> narrateReport(result);
            default -> "Done.";
        };
    }

    private String narrateJobs(Object result) {
        if (!(result instanceof List<?> list) || list.isEmpty()) {
            return "You have no jobs yet. Describe a problem and I can report one.";
        }
        StringBuilder sb = new StringBuilder("Here are your jobs:");
        for (Object o : list) {
            if (o instanceof JobDtos.JobSummaryView j) {
                sb.append("\n• ").append(j.title() == null ? "Untitled" : j.title())
                        .append(" — ").append(readable(j.status().name()));
            }
        }
        return sb.toString();
    }

    private String narrateServices(Object result) {
        if (!(result instanceof List<?> list) || list.isEmpty()) {
            return "The service list is not available right now.";
        }
        StringBuilder sb = new StringBuilder("Here is what we cover:");
        for (Object o : list) {
            if (o instanceof CatalogDtos.ServiceCard c && c.bookable()) {
                sb.append("\n• ").append(c.name());
                if (c.typicalLowCents() != null && c.typicalHighCents() != null) {
                    sb.append(" — typically ").append(money(c.typicalLowCents()))
                            .append("–").append(money(c.typicalHighCents()));
                }
            }
        }
        // Every trade may be unbookable, in which case the loop above added nothing.
        return sb.length() == "Here is what we cover:".length()
                ? "Nothing is bookable in your area at the moment." : sb.toString();
    }

    private String narrateProperties(Object result) {
        if (!(result instanceof List<?> list) || list.isEmpty()) {
            return "You have no properties saved yet.";
        }
        StringBuilder sb = new StringBuilder("Your properties:");
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                sb.append("\n• ").append(m.get("label"));
            }
        }
        return sb.toString();
    }

    private String narrateReport(Object result) {
        if (result instanceof JobDtos.JobDetailView j) {
            return "Reported \"" + j.title() + "\". Reference #"
                    + j.id().toString().substring(0, 8).toUpperCase(Locale.ROOT)
                    + ". You can track it from your dashboard.";
        }
        return "Reported.";
    }

    // ---- helpers ----

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    /** A title short enough to read in a list, without cutting a word in half. */
    static String summarise(String message) {
        String one = message.replaceAll("\\s+", " ").trim();
        if (one.length() <= 60) return one;
        int cut = one.lastIndexOf(' ', 60);
        return one.substring(0, cut < 20 ? 60 : cut) + "…";
    }

    private static String label(Property p) {
        return p.getLabel() != null && !p.getLabel().isBlank() ? p.getLabel()
                : (p.getLine1() == null ? "your property" : p.getLine1());
    }

    private static String readable(String status) {
        return status.replace('_', ' ');
    }

    private static String money(long cents) {
        return "$" + (cents / 100);
    }
}
