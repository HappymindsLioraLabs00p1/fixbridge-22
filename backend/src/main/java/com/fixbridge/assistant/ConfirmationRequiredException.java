package com.fixbridge.assistant;

import com.fixbridge.common.error.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Raised when the assistant tried to do something that changes state and the customer has not agreed
 * to it yet.
 *
 * <p>This is a control-flow signal rather than a failure: it carries the tool and the exact arguments
 * back to the caller, so the pending action can be shown to the customer and then re-submitted
 * unchanged once they say yes. Re-submitting the <em>same</em> arguments is the point — a confirmation
 * that let the model rewrite what it was confirming would be worth nothing.
 *
 * <p>It extends {@link ApiException} with a 409 so that if it ever escapes the assistant layer without
 * being handled, the customer sees "needs confirmation" rather than a 500.
 */
public class ConfirmationRequiredException extends ApiException {

    private final transient String toolName;
    private final transient Map<String, Object> arguments;

    public ConfirmationRequiredException(String toolName, Map<String, Object> arguments, String prompt) {
        super(HttpStatus.CONFLICT, prompt);
        this.toolName = toolName;
        this.arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    /** The tool awaiting approval. */
    public String getToolName() {
        return toolName;
    }

    /** The arguments to replay verbatim on confirmation. */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /** The plain-language question to put to the customer. */
    public String getPrompt() {
        return getMessage();
    }
}
