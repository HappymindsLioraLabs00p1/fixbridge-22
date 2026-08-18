package com.fixbridge.assistant;

import com.fixbridge.assistant.dto.AssistantDtos;
import com.fixbridge.auth.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The assistant, for text and — later — voice.
 *
 * <p>One endpoint for both on purpose. Speech is a transport concern: audio becomes a transcript,
 * the transcript comes here, and the answer goes back out as text that may then be spoken. Giving
 * voice its own endpoint would let the two drift into different capabilities, which is how a spoken
 * assistant ends up able to do something the typed one refuses.
 *
 * <p>Separate from {@code /api/repair-chat}, which is the guided step-by-step repair flow. This one
 * answers questions about the customer's own account and can file a job.
 */
@RestController
@RequestMapping("/api/assistant")
@PreAuthorize("isAuthenticated()")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    /** Say something to the assistant, or confirm an action it proposed. */
    @PostMapping("/message")
    public AssistantDtos.Reply message(@Valid @RequestBody AssistantDtos.MessageRequest req) {
        return service.handle(SecurityUtil.currentUser(), req);
    }

    /** What the assistant is able to do — the tool list, not a prose description of it. */
    @GetMapping("/capabilities")
    public List<Map<String, Object>> capabilities() {
        return service.capabilities();
    }
}
