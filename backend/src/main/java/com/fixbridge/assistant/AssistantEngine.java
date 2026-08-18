package com.fixbridge.assistant;

import com.fixbridge.auth.AuthUser;

import java.util.Map;

/**
 * Turns what the customer said into either an answer or a tool to run.
 *
 * <p>Deliberately narrow. The engine chooses; it never acts. Execution goes through
 * {@link AssistantToolRegistry}, so swapping a deterministic engine for a language model cannot widen
 * what the assistant is able to do — a new engine inherits exactly the same tool set and the same
 * confirmation gate.
 */
public interface AssistantEngine {

    /**
     * What to do about this message.
     *
     * @param toolName the tool to run, or {@code null} to just say {@code reply} and stop.
     */
    record Decision(String reply, String toolName, Map<String, Object> arguments) {

        public static Decision say(String reply) {
            return new Decision(reply, null, Map.of());
        }

        public static Decision use(String toolName, Map<String, Object> arguments) {
            return new Decision(null, toolName, arguments);
        }
    }

    Decision decide(AuthUser user, String message);

    /** Put a tool's result into words for the customer. */
    String narrate(String toolName, Object result);

    /** For logging and for showing the customer which engine answered. */
    String name();
}
