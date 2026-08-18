package com.fixbridge.assistant;

import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way the assistant reaches the application.
 *
 * <p>Every tool call funnels through {@link #run}, which is what makes "what can the AI do?" a
 * question with an answer. Two things are enforced here rather than in a prompt, because a prompt is
 * a request and this is a rule:
 *
 * <ul>
 *   <li>A name the registry does not know is refused. The model cannot invent a tool.
 *   <li>A {@linkplain AssistantTool#mutating() mutating} tool will not run unless the customer has
 *       explicitly confirmed <em>this</em> action. Anything that books, cancels or spends money stops
 *       here until a human says yes.
 * </ul>
 *
 * <p>The confirmation flag is a parameter rather than state on the conversation on purpose: it has to
 * be supplied per call, so a customer who confirmed one booking has not thereby confirmed the next
 * thing the model decides to do.
 */
@Component
public class AssistantToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AssistantToolRegistry.class);

    private final Map<String, AssistantTool> byName = new LinkedHashMap<>();

    public AssistantToolRegistry(List<AssistantTool> tools) {
        for (AssistantTool tool : tools) {
            AssistantTool clash = byName.put(tool.name(), tool);
            if (clash != null) {
                // Two tools answering to one name means the model's choice silently resolves to
                // whichever Spring happened to order last. Refuse to start instead.
                throw new IllegalStateException("Duplicate assistant tool name: " + tool.name()
                        + " (" + clash.getClass().getName() + " and " + tool.getClass().getName() + ")");
            }
        }
        log.info("Assistant tools registered: {} ({} read-only)", byName.keySet(),
                byName.values().stream().filter(t -> !t.mutating()).count());
    }

    /** Tool definitions to hand a model, in registration order. */
    public List<Map<String, Object>> specs() {
        return byName.values().stream()
                .map(t -> Map.<String, Object>of(
                        "name", t.name(),
                        "description", t.description(),
                        "parameters", t.parameters()))
                .toList();
    }

    /** Whether a named tool changes state. Unknown names are treated as mutating — see {@link #run}. */
    public boolean isMutating(String name) {
        AssistantTool tool = byName.get(name);
        return tool == null || tool.mutating();
    }

    /**
     * Run a tool on behalf of {@code user}.
     *
     * @param confirmed the customer has explicitly approved this specific action. Ignored for
     *                  read-only tools; required for every other one.
     */
    public Object run(AuthUser user, String name, Map<String, Object> arguments, boolean confirmed) {
        AssistantTool tool = byName.get(name);
        if (tool == null) {
            log.warn("Assistant requested unknown tool '{}'", name);
            throw ApiException.badRequest("I do not know how to do that.");
        }
        if (tool.mutating() && !confirmed) {
            // Not an error the customer caused — the caller is expected to put the prompt to them and
            // then replay this same call with the same arguments.
            log.info("Refusing unconfirmed mutating tool '{}' for user {}", name, user.id());
            throw new ConfirmationRequiredException(
                    name, arguments, tool.confirmationPrompt(user, arguments == null ? Map.of() : arguments));
        }
        log.info("Assistant tool '{}' for user {}{}", name, user.id(), tool.mutating() ? " (confirmed)" : "");
        return tool.execute(user, arguments == null ? Map.of() : arguments);
    }
}
