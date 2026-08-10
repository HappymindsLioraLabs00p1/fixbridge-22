package com.fixbridge.common.enums;

/** Matches PostgreSQL {@code conversation_status} — what the assistant needs next. */
public enum ConversationStatus {
    NEED_MORE_INFORMATION, NEED_IMAGE, REPAIR_PLAN_READY, PROFESSIONAL_REQUIRED, EMERGENCY
}
