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

    /** Change your own password while signed in. Requires the current one — a stolen session alone
     *  must not be enough to lock the real owner out. */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, message = "Password must be at least 8 characters") String newPassword
    ) {}

    /** Deliberately generic so a caller cannot tell whether an account exists. */
    public record MessageResponse(String message) {}

    // ---- Passwordless ("Continue with Phone / Email") ----

    /** channel is "sms" or "email"; destination is the phone number or email address as typed. */
    public record OtpSendRequest(@NotBlank String channel, @NotBlank String destination) {}

    public record OtpVerifyRequest(
            @NotBlank String channel,
            @NotBlank String destination,
            @NotBlank String code
    ) {}

    /**
     * Either {@code tokens} (existing account — signed in) or {@code signupTicket} (new person —
     * carry the ticket into onboarding). Exactly one is set.
     */
    public record OtpVerifyResponse(TokenResponse tokens, String signupTicket, boolean newUser) {}

    /**
     * Finish creating an account after an OTP proved the destination. Email is required when the
     * proof was a phone (accounts are keyed by email); ignored when the proof WAS the email.
     */
    public record OtpCompleteRequest(
            @NotBlank String signupTicket,
            @NotBlank String fullName,
            String email
    ) {}

    public record UserView(UUID id, String email, String fullName, List<UserRole> roles) {}

    public record TokenResponse(String accessToken, String refreshToken, String tokenType, UserView user) {
        public static TokenResponse bearer(String access, String refresh, UserView user) {
            return new TokenResponse(access, refresh, "Bearer", user);
        }
    }
}
