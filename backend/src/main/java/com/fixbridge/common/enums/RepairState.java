package com.fixbridge.common.enums;

/**
 * The precise state of a repair, mirroring the AI service state machine.
 *
 * <p>Distinct from {@link ConversationStatus}, which stays a small, stable set describing what the
 * client must do next. This says where the repair actually is, and is what a progress indicator
 * renders from. The AI service is authoritative; this enum exists so the value can be persisted
 * and typed rather than passed around as a string.
 */
public enum RepairState {
    NEW,
    COLLECTING_INFORMATION,
    WAITING_FOR_IMAGE,
    IMAGE_ANALYSIS,
    SAFETY_CHECK,
    INSUFFICIENT_INFORMATION,
    SAFE_DIY,
    PROFESSIONAL_REQUIRED,
    EMERGENCY,
    REPAIR_PLAN_CREATED,
    STEP_IN_PROGRESS,
    WAITING_FOR_VERIFICATION,
    STEP_VERIFICATION,
    STEP_FAILED,
    REPAIR_COMPLETED,
    CONTRACTOR_SEARCH,
    CONTRACTOR_REQUESTED,
    CONTRACTOR_ACCEPTED,
    ESCALATED,
    CLOSED
}
