package com.fixbridge.assistant;

import com.fixbridge.assistant.dto.AssistantDtos;
import com.fixbridge.auth.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * One turn of the assistant: decide, run, answer.
 *
 * <p>The engine picks a tool and the registry runs it. Neither half can widen the other — an engine
 * cannot execute, and the registry does not care what chose the tool — which is what keeps swapping in
 * a language model from changing the assistant's reach.
 *
 * <h2>What the confirmation gate is for</h2>
 *
 * <p>Worth being precise, because it is easy to mistake: the gate protects the customer from the
 * <em>assistant</em> acting unilaterally, not from themselves. The customer is allowed to file a job —
 * they can do it from the UI. So {@code confirmed} arriving from their own client is not a hole: it is
 * the customer saying yes, which is exactly what is being asked for. What the gate prevents is a model
 * quietly deciding to book something the customer never agreed to, and it does so by making the
 * approval a separate, explicit round trip that names the action.
 *
 * <p>Every tool is user-scoped regardless, so a client that fabricated a confirmation could still only
 * reach its own data.
 */
@Service
public class AssistantService {

    private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

    private final AssistantEngine engine;
    private final AssistantToolRegistry registry;

    public AssistantService(AssistantEngine engine, AssistantToolRegistry registry) {
        this.engine = engine;
        this.registry = registry;
    }

    public AssistantDtos.Reply handle(AuthUser user, AssistantDtos.MessageRequest req) {
        // The customer is answering a confirmation. Replay the action they were shown, verbatim.
        if (req.confirmed() && req.confirmTool() != null && !req.confirmTool().isBlank()) {
            Object result = registry.run(user, req.confirmTool(), req.confirmArguments(), true);
            return new AssistantDtos.Reply(
                    engine.narrate(req.confirmTool(), result), null,
                    List.of(req.confirmTool()), engine.name());
        }

        AssistantEngine.Decision decision = engine.decide(user, req.message());
        if (decision.toolName() == null) {
            return new AssistantDtos.Reply(decision.reply(), null, List.of(), engine.name());
        }

        try {
            Object result = registry.run(user, decision.toolName(), decision.arguments(), false);
            return new AssistantDtos.Reply(
                    engine.narrate(decision.toolName(), result), null,
                    List.of(decision.toolName()), engine.name());
        } catch (ConfirmationRequiredException e) {
            // Not a failure: the assistant is asking. Hand the pending action back untouched so the
            // client can echo it on approval.
            log.info("Assistant awaiting confirmation of '{}' for user {}", e.getToolName(), user.id());
            return new AssistantDtos.Reply(
                    e.getPrompt(),
                    new AssistantDtos.PendingAction(e.getToolName(), e.getArguments(), e.getPrompt()),
                    List.of(), engine.name());
        }
    }

    /** The tools this assistant can reach, for transparency in the UI. */
    public List<Map<String, Object>> capabilities() {
        return registry.specs();
    }
}
