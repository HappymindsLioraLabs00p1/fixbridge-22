package com.fixbridge.auth;

import com.fixbridge.common.enums.UserRole;

import java.util.List;
import java.util.UUID;

/** The authenticated principal placed in the security context for each request. */
public record AuthUser(UUID id, String email, List<UserRole> roles) {

    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }
}
