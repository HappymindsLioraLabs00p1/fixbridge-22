package com.fixbridge.auth;

import com.fixbridge.auth.dto.AuthDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthTokenService authTokenService;

    public AuthController(AuthService authService, AuthTokenService authTokenService) {
        this.authService = authService;
        this.authTokenService = authTokenService;
    }

    @PostMapping("/register")
    public AuthDtos.TokenResponse register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return authService.register(req);
    }

    @PostMapping("/login")
    public AuthDtos.TokenResponse login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/refresh")
    public AuthDtos.TokenResponse refresh(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        return authService.refresh(req);
    }

    /**
     * Start a password reset. Always returns the same message — revealing whether an address has an
     * account would let an attacker enumerate users.
     */
    @PostMapping("/forgot-password")
    public AuthDtos.MessageResponse forgotPassword(@Valid @RequestBody AuthDtos.ForgotPasswordRequest req) {
        authTokenService.requestPasswordReset(req.email());
        return new AuthDtos.MessageResponse(
                "If an account exists for that address, we've sent a link to reset the password.");
    }

    @PostMapping("/reset-password")
    public AuthDtos.MessageResponse resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordRequest req) {
        authTokenService.resetPassword(req.token(), req.newPassword());
        return new AuthDtos.MessageResponse("Your password has been changed. You can sign in now.");
    }

    @PostMapping("/verify-email")
    public AuthDtos.MessageResponse verifyEmail(@Valid @RequestBody AuthDtos.VerifyEmailRequest req) {
        authTokenService.verifyEmail(req.token());
        return new AuthDtos.MessageResponse("Your email address is confirmed.");
    }

    @GetMapping("/me")
    public AuthDtos.UserView me() {
        AuthUser user = SecurityUtil.currentUser();
        return new AuthDtos.UserView(user.id(), user.email(), null, user.roles());
    }
}
