package com.fixbridge.auth;

import com.fixbridge.common.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience access to the current {@link AuthUser}. */
public final class SecurityUtil {

    private SecurityUtil() {}

    public static AuthUser currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthUser user)) {
            throw ApiException.forbidden();
        }
        return user;
    }
}
