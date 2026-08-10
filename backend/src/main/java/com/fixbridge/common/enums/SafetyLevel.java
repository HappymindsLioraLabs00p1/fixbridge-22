package com.fixbridge.common.enums;

/** Matches PostgreSQL {@code safety_level}. INSUFFICIENT_INFORMATION is deliberately distinct from
 *  SAFE_DIY: not knowing is not the same as knowing it's safe. */
public enum SafetyLevel {
    SAFE_DIY, PROFESSIONAL_REQUIRED, EMERGENCY, INSUFFICIENT_INFORMATION;

    public boolean allowsDiy() {
        return this == SAFE_DIY;
    }
}
