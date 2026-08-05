package com.fixbridge.common.enums;

/**
 * Application roles. Constant names deliberately match the PostgreSQL {@code user_role} enum labels
 * (lowercase) so Hibernate's {@code NAMED_ENUM} mapping binds directly to the native enum type.
 */
public enum UserRole {
    customer, landlord, agent, contractor, admin, partner
}
