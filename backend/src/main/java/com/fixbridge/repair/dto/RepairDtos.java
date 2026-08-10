package com.fixbridge.repair.dto;

import com.fixbridge.common.enums.*;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RepairDtos {

    private RepairDtos() {}

    public record SendMessageRequest(
            @Size(max = 4000) String text,
            /** Object keys from the media upload flow — never raw bytes through this API. */
            List<String> imageKeys
    ) {}

    public record StepView(
            UUID id, int number, String instruction, String why,
            List<String> tools, List<String> warnings, String expectedResult,
            boolean requiresImageVerification, StepState state
    ) {}

    public record PlanView(
            UUID id, String problem, Integer estimatedMinutes,
            List<String> stopConditions, List<StepView> steps
    ) {}

    /** What the chat UI renders. Structured, so the client never parses assistant prose. */
    public record ConversationView(
            UUID id, ConversationStatus status, SafetyLevel safetyLevel,
            String category, String problem, String message,
            List<String> quickReplies, boolean requiresImage, PlanView plan
    ) {}

    public record ConversationSummary(
            UUID id, String category, String problem, ConversationStatus status,
            SafetyLevel safetyLevel, Instant createdAt
    ) {}

    public record VerifyRequest(List<String> imageKeys) {}

    public record VerificationView(
            UUID stepId, int stepNumber, VerificationResult result,
            BigDecimal confidence, String reason, String nextAction
    ) {}
}
