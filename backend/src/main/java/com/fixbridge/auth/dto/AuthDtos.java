package com.fixbridge.auth.dto;

import com.fixbridge.common.enums.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Auth request/response payloads. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String password,
            String fullName,
            /** One of customer, contractor, landlord, agent. Admin cannot self-register. */
            UserRole role
    ) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password
    ) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record ForgotPasswordRequest(@Email @NotBlank String email) {}

    public record ResetPasswordRequest(
            @NotBlank String token,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String newPassword
    ) {}

    public record VerifyEmailRequest(@NotBlank String token) {}

    /** Deliberately generic so a caller cannot tell whether an account exists. */
    public record MessageResponse(String message) {}

    public record UserView(UUID id, String email, String fullName, List<UserRole> roles) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, UserView user) {
        public static TokenResponse bearer(String access, String refresh, UserView user) {
            return new TokenResponse(access, refresh, "Bearer", user);
        }
    }
}
