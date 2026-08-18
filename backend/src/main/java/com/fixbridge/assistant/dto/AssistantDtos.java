package com.fixbridge.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** Wire types for the assistant. */
public final class AssistantDtos {

    private AssistantDtos() {}

    /**
     * A turn in the conversation.
     *
     * <p>When the customer is answering a confirmation, the client sends back the pending action
     * exactly as it was handed out, along with {@code confirmed}. Replaying the arguments rather than
     * re-deriving them is what makes the approval refer to the thing that was actually shown.
     */
    public record MessageRequest(
            @NotBlank @Size(max = 2000) String message,
            String confirmTool,
            Map<String, Object> confirmArguments,
            boolean confirmed
    ) {}

    /** An action waiting on the customer's yes. Echo it back with {@code confirmed} to proceed. */
    public record PendingAction(String tool, Map<String, Object> arguments, String prompt) {}

    /**
     * @param pending non-null when the assistant is waiting to be told to go ahead
     * @param toolsUsed named so the customer can see what was actually consulted, rather than taking
     *                  the answer on trust
     */
    public record Reply(String reply, PendingAction pending, List<String> toolsUsed, String engine) {}
}
