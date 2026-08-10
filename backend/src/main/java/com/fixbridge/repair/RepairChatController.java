package com.fixbridge.repair;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.repair.dto.RepairDtos;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The guided-repair chat. Separate from JobController on purpose: this is the assisted
 * troubleshooting flow, which may or may not become a dispatched job.
 */
@RestController
@RequestMapping("/api/repair-chat")
@PreAuthorize("isAuthenticated()")
public class RepairChatController {

    private final RepairChatService service;

    public RepairChatController(RepairChatService service) {
        this.service = service;
    }

    /** Begin a conversation. */
    @PostMapping
    public RepairDtos.ConversationView start() {
        return service.start(SecurityUtil.currentUser());
    }

    /** The customer's past conversations, newest first. */
    @GetMapping
    public List<RepairDtos.ConversationSummary> mine() {
        return service.mine(SecurityUtil.currentUser());
    }

    /** Send a message (and optionally photos); get the assistant's next move. */
    @PostMapping("/{conversationId}/messages")
    public RepairDtos.ConversationView send(@PathVariable UUID conversationId,
                                            @Valid @RequestBody RepairDtos.SendMessageRequest req) {
        return service.send(SecurityUtil.currentUser(), conversationId, req);
    }

    /** Submit a progress photo for a step. */
    @PostMapping("/steps/{stepId}/verify")
    public RepairDtos.VerificationView verify(@PathVariable UUID stepId,
                                              @Valid @RequestBody RepairDtos.VerifyRequest req) {
        return service.verifyStep(SecurityUtil.currentUser(), stepId, req.imageKeys());
    }
}
