package com.fixbridge.assistant;

import com.fixbridge.auth.AuthUser;

import java.util.Map;

/**
 * One thing the assistant is allowed to do on a customer's behalf.
 *
 * <p>The assistant never reaches into repositories itself. Everything it can see or change goes
 * through a tool, so the set of implementations of this interface <em>is</em> the list of things a
 * language model can cause to happen — reviewable in one place rather than inferred from a prompt.
 *
 * <p>Two rules hold for every implementation:
 *
 * <ul>
 *   <li><b>The caller is the subject.</b> Each tool receives the authenticated {@link AuthUser} and
 *       must scope its answer to that user. A tool that takes an id from the model's arguments has to
 *       verify ownership itself — the model is not a trusted source of identity, and a hallucinated
 *       or copied job id must not return somebody else's data.
 *   <li><b>{@link #mutating()} is honest.</b> The registry refuses to run a mutating tool that has
 *       not been explicitly confirmed by the customer. A tool that under-reports itself as read-only
 *       would slip straight past that gate, so this flag is a security boundary and not a hint.
 * </ul>
 */
public interface AssistantTool {

    /** Stable identifier the model refers to, e.g. {@code list_my_jobs}. Never renamed casually. */
    String name();

    /** What the tool does, written for the model to choose against. One sentence. */
    String description();

    /**
     * JSON-schema-shaped description of the accepted arguments, for the model's tool definition.
     * An empty map means the tool takes none.
     */
    Map<String, Object> parameters();

    /**
     * Whether running this tool changes state — books, cancels, pays, or writes. Read-only tools may
     * run freely; mutating ones require the customer's explicit confirmation first.
     *
     * <p>Defaults to {@code true}: a tool author who forgets to think about this gets the safe
     * answer, and the worst outcome is a confirmation prompt nobody needed rather than an unwanted
     * booking.
     */
    default boolean mutating() {
        return true;
    }

    /**
     * The question put to the customer before a mutating tool runs, in their own terms.
     *
     * <p>Every mutating tool must override this with something that names what is about to happen —
     * the address, the title, the amount. A confirmation the customer cannot check is not consent:
     * "Do you want me to go ahead?" tells them nothing about which property they are about to book
     * work on, so approving it is a reflex rather than a decision.
     *
     * <p>Takes the user as well as the arguments so the prompt can resolve ids into names. The default
     * exists only for read-only tools, which never reach the gate.
     */
    default String confirmationPrompt(AuthUser user, Map<String, Object> arguments) {
        return "Do you want me to go ahead?";
    }

    /** Run the tool for this user. Arguments are whatever the model supplied, so treat them as input. */
    Object execute(AuthUser user, Map<String, Object> arguments);
}
