package com.fixbridge.repair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixbridge.auth.AuthUser;
import com.fixbridge.common.enums.*;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.repair.dto.RepairDtos;
import com.fixbridge.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Orchestrates the guided-repair conversation.
 *
 * <p>Java owns every piece of state; the Python service is handed the history it needs on each
 * call and remembers nothing. Image object keys are resolved to short-lived signed URLs only at the
 * moment of the call, so nothing durable ever holds a URL that will expire.
 */
@Service
public class RepairChatService {

    private static final Logger log = LoggerFactory.getLogger(RepairChatService.class);

    private final ConversationRepository conversations;
    private final ConversationMessageRepository messages;
    private final RepairPlanRepository plans;
    private final RepairStepRepository steps;
    private final StepVerificationRepository verifications;
    private final RepairAiClient ai;
    private final StorageService storage;
    private final ObjectMapper objectMapper;

    public RepairChatService(ConversationRepository conversations,
                             ConversationMessageRepository messages,
                             RepairPlanRepository plans,
                             RepairStepRepository steps,
                             StepVerificationRepository verifications,
                             RepairAiClient ai, StorageService storage, ObjectMapper objectMapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.plans = plans;
        this.steps = steps;
        this.verifications = verifications;
        this.ai = ai;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RepairDtos.ConversationView start(AuthUser user) {
        RepairConversation c = new RepairConversation();
        c.setCustomerId(user.id());
        conversations.save(c);
        return view(c, null, "What's gone wrong? Describe it in your own words, or send a photo.");
    }

    @Transactional(readOnly = true)
    public List<RepairDtos.ConversationSummary> mine(AuthUser user) {
        return conversations.findByCustomerIdOrderByCreatedAtDesc(user.id()).stream()
                .map(c -> new RepairDtos.ConversationSummary(
                        c.getId(), c.getCategory(), c.getProblem(), c.getStatus(),
                        c.getSafetyLevel(), c.getCreatedAt()))
                .toList();
    }

    /** Add the customer's turn, ask the assistant what's next, and persist the outcome. */
    @Transactional
    public RepairDtos.ConversationView send(AuthUser user, UUID conversationId,
                                            RepairDtos.SendMessageRequest req) {
        RepairConversation conversation = require(user, conversationId);

        ConversationMessage inbound = new ConversationMessage();
        inbound.setConversationId(conversationId);
        inbound.setRole("customer");
        inbound.setBody(req.text());
        inbound.setImageKeys(req.imageKeys() == null ? new String[0]
                : req.imageKeys().toArray(String[]::new));
        messages.save(inbound);

        JsonNode reply;
        try {
            reply = ai.converse(replayHistory(conversationId), conversation.getRepairState());
        } catch (RepairAiClient.RepairAiUnavailableException e) {
            // The assistant being down must not lose the customer's message — it's saved above, and
            // they're told plainly rather than shown an error.
            log.warn("Assistant unavailable for conversation {}: {}", conversationId, e.getMessage());
            return view(conversation, currentPlan(conversationId),
                    "I'm having trouble thinking right now. Your message is saved — please try again "
                            + "in a moment.");
        }

        applyReply(conversation, reply);
        String assistantText = reply.path("message").asText("");
        ConversationMessage outbound = new ConversationMessage();
        outbound.setConversationId(conversationId);
        outbound.setRole("assistant");
        outbound.setBody(assistantText);
        messages.save(outbound);

        RepairPlanEntity plan = null;
        if (reply.hasNonNull("repair_plan") && !reply.path("repair_plan").isNull()) {
            plan = savePlan(conversationId, reply.path("repair_plan"));
        }
        return view(conversation, plan == null ? currentPlan(conversationId) : plan, assistantText,
                reply);
    }

    /** Check a progress photo for one step. */
    @Transactional
    public RepairDtos.VerificationView verifyStep(AuthUser user, UUID stepId, List<String> imageKeys) {
        RepairStep step = steps.findById(stepId).orElseThrow(() -> ApiException.notFound("Step"));
        RepairPlanEntity plan = plans.findById(step.getPlanId())
                .orElseThrow(() -> ApiException.notFound("Plan"));
        require(user, plan.getConversationId());

        if (imageKeys == null || imageKeys.isEmpty()) {
            throw ApiException.badRequest("A photo is needed to check this step");
        }
        // Signed only for the duration of the call.
        List<String> urls = imageKeys.stream().map(storage::createDownloadUrl).toList();

        JsonNode reply;
        try {
            reply = ai.verifyStep(step.getStepNumber(), step.getInstruction(),
                    step.getExpectedResult(), urls);
        } catch (RepairAiClient.RepairAiUnavailableException e) {
            // Unreachable must not read as verified.
            return new RepairDtos.VerificationView(step.getId(), step.getStepNumber(),
                    VerificationResult.UNCERTAIN, BigDecimal.ZERO,
                    "I couldn't check that photo just now.",
                    "Please try again in a moment.");
        }

        VerificationResult result = enumOrDefault(reply.path("verification").asText(),
                VerificationResult.UNCERTAIN);
        BigDecimal confidence = BigDecimal.valueOf(reply.path("confidence").asDouble(0));

        StepVerification record = new StepVerification();
        record.setStepId(stepId);
        record.setResult(result);
        record.setConfidence(confidence);
        record.setReason(reply.path("reason").asText(""));
        record.setImageKeys(imageKeys.toArray(String[]::new));
        verifications.save(record);

        // Only a confident pass advances the step; everything else leaves it open.
        step.setState(switch (result) {
            case STEP_COMPLETED -> StepState.verified;
            case ESCALATE -> StepState.failed;
            default -> StepState.in_progress;
        });
        steps.save(step);

        return new RepairDtos.VerificationView(step.getId(), step.getStepNumber(), result,
                confidence, record.getReason(), reply.path("next_action").asText(""));
    }

    // ---- internals --------------------------------------------------------------------------

    private RepairConversation require(AuthUser user, UUID conversationId) {
        RepairConversation c = conversations.findById(conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation"));
        if (!c.getCustomerId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        return c;
    }

    /** Rebuild the transcript for the assistant, signing image keys at call time. */
    private List<Map<String, Object>> replayHistory(UUID conversationId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConversationMessage m : messages.findByConversationIdOrderByCreatedAtAsc(conversationId)) {
            List<String> urls = m.getImageKeys() == null ? List.of()
                    : Arrays.stream(m.getImageKeys()).map(storage::createDownloadUrl).toList();
            out.add(Map.of("role", m.getRole(),
                    "text", m.getBody() == null ? "" : m.getBody(),
                    "image_urls", urls));
        }
        return out;
    }

    private void applyReply(RepairConversation conversation, JsonNode reply) {
        conversation.setStatus(enumOrDefault(reply.path("status").asText(),
                ConversationStatus.NEED_MORE_INFORMATION));
        // An unrecognised state means the AI service knows a state this build does not. Keeping the
        // previous one is better than resetting to NEW, which would rewind the machine.
        conversation.setRepairState(enumOrDefault(reply.path("state").asText(),
                conversation.getRepairState()));
        conversation.setSafetyLevel(enumOrDefault(reply.path("safety").path("level").asText(),
                SafetyLevel.INSUFFICIENT_INFORMATION));
        if (reply.hasNonNull("category")) conversation.setCategory(reply.path("category").asText());
        if (reply.hasNonNull("problem")) conversation.setProblem(reply.path("problem").asText());
        conversations.save(conversation);
    }

    private RepairPlanEntity savePlan(UUID conversationId, JsonNode node) {
        RepairPlanEntity plan = new RepairPlanEntity();
        plan.setConversationId(conversationId);
        plan.setProblem(node.path("problem").asText(""));
        plan.setCategory(node.path("category").asText(null));
        plan.setSafetyLevel(enumOrDefault(node.path("safety_level").asText(), SafetyLevel.SAFE_DIY));
        plan.setEstimatedMinutes(node.hasNonNull("estimated_minutes")
                ? node.path("estimated_minutes").asInt() : null);
        List<String> stops = new ArrayList<>();
        node.path("stop_conditions").forEach(s -> stops.add(s.asText()));
        plan.setStopConditions(stops.toArray(String[]::new));
        plan.setRawJson(objectMapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {}));
        plans.save(plan);

        for (JsonNode s : node.path("steps")) {
            RepairStep step = new RepairStep();
            step.setPlanId(plan.getId());
            step.setStepNumber(s.path("number").asInt());
            step.setInstruction(s.path("instruction").asText(""));
            step.setWhy(s.path("why").asText(null));
            step.setTools(toArray(s.path("tools")));
            step.setParts(toArray(s.path("parts")));
            step.setWarnings(toArray(s.path("warnings")));
            step.setExpectedResult(s.path("expected_result").asText(null));
            step.setRequiresImageVerification(s.path("requires_image_verification").asBoolean(false));
            steps.save(step);
        }
        log.info("Saved repair plan {} with {} steps", plan.getId(), node.path("steps").size());
        return plan;
    }

    private RepairPlanEntity currentPlan(UUID conversationId) {
        return plans.findFirstByConversationIdOrderByCreatedAtDesc(conversationId).orElse(null);
    }

    private RepairDtos.ConversationView view(RepairConversation c, RepairPlanEntity plan,
                                             String message) {
        return view(c, plan, message, null);
    }

    private RepairDtos.ConversationView view(RepairConversation c, RepairPlanEntity plan,
                                             String message, JsonNode reply) {
        List<String> quickReplies = new ArrayList<>();
        boolean requiresImage = false;
        if (reply != null) {
            reply.path("quick_replies").forEach(q -> quickReplies.add(q.asText()));
            requiresImage = reply.path("requires_image").asBoolean(false);
        }
        List<RepairDtos.StepView> stepViews = plan == null ? List.of()
                : steps.findByPlanIdOrderByStepNumberAsc(plan.getId()).stream()
                    .map(s -> new RepairDtos.StepView(s.getId(), s.getStepNumber(), s.getInstruction(),
                            s.getWhy(), List.of(s.getTools()), List.of(s.getWarnings()),
                            s.getExpectedResult(), s.isRequiresImageVerification(), s.getState()))
                    .toList();
        return new RepairDtos.ConversationView(
                c.getId(), c.getStatus(), c.getRepairState(), c.getSafetyLevel(), c.getCategory(), c.getProblem(),
                message, quickReplies, requiresImage,
                plan == null ? null : new RepairDtos.PlanView(plan.getId(), plan.getProblem(),
                        plan.getEstimatedMinutes(), List.of(plan.getStopConditions()), stepViews));
    }

    private static String[] toArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out.toArray(String[]::new);
    }

    private static <E extends Enum<E>> E enumOrDefault(String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            @SuppressWarnings("unchecked")
            E parsed = (E) Enum.valueOf(fallback.getDeclaringClass(), value);
            return parsed;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
