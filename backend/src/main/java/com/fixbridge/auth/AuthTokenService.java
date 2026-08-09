package com.fixbridge.auth;

import com.fixbridge.common.error.ApiException;
import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.notification.EmailSender;
import com.fixbridge.user.Profile;
import com.fixbridge.user.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Password-reset and email-verification tokens (FR-AUTH-5).
 *
 * <p>Security properties: tokens are 256 bits of randomness, stored only as a SHA-256 hash, are
 * single-use and expire. Requesting a reset never reveals whether an account exists.
 */
@Service
public class AuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long RESET_TTL_MINUTES = 60;
    private static final long VERIFY_TTL_HOURS = 48;

    private final AuthTokenRepository tokens;
    private final ProfileRepository profiles;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender email;
    private final FixBridgeProperties props;

    public AuthTokenService(AuthTokenRepository tokens, ProfileRepository profiles,
                            PasswordEncoder passwordEncoder, EmailSender email, FixBridgeProperties props) {
        this.tokens = tokens;
        this.profiles = profiles;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.props = props;
    }

    /**
     * Start a password reset. Always succeeds from the caller's perspective — we must not leak
     * whether the address belongs to an account (enumeration).
     */
    @Transactional
    public void requestPasswordReset(String emailAddress) {
        profiles.findByEmailIgnoreCase(emailAddress).ifPresentOrElse(profile -> {
            String raw = issue(profile, AuthToken.Purpose.password_reset,
                    Instant.now().plus(RESET_TTL_MINUTES, ChronoUnit.MINUTES));
            String link = props.brand().domain().startsWith("http")
                    ? props.brand().domain() + "/reset-password?token=" + raw
                    : "https://" + props.brand().domain() + "/reset-password?token=" + raw;
            email.sendEmail(profile.getEmail(),
                    "Reset your " + props.brand().name() + " password",
                    "<p>We received a request to reset your password.</p>"
                            + "<p><a href=\"" + link + "\">Choose a new password</a></p>"
                            + "<p>This link expires in " + RESET_TTL_MINUTES
                            + " minutes and can be used once. If you didn't ask for this, you can ignore it.</p>");
        }, () -> log.info("Password reset requested for an address with no account — no email sent"));
    }

    /** Complete a password reset. The token is consumed whether or not it is reused later. */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        AuthToken token = consume(rawToken, AuthToken.Purpose.password_reset);
        Profile profile = profiles.findById(token.getUserId())
                .orElseThrow(() -> ApiException.notFound("Account"));
        profile.setPasswordHash(passwordEncoder.encode(newPassword));
        profiles.save(profile);
        log.info("Password reset completed for {}", profile.getId());
    }

    /**
     * Change the password of the signed-in account. The current password is required, so a stolen
     * access token cannot be used to take the account over permanently.
     */
    @Transactional
    public void changePassword(java.util.UUID userId, String currentPassword, String newPassword) {
        Profile profile = profiles.findById(userId).orElseThrow(() -> ApiException.notFound("Account"));
        if (!passwordEncoder.matches(currentPassword, profile.getPasswordHash())) {
            throw ApiException.badRequest("Your current password is not correct");
        }
        if (passwordEncoder.matches(newPassword, profile.getPasswordHash())) {
            throw ApiException.badRequest("Choose a password you haven't used here before");
        }
        profile.setPasswordHash(passwordEncoder.encode(newPassword));
        profiles.save(profile);
        log.info("Password changed by {}", profile.getId());
    }

    /** Send (or re-send) an email-verification link. */
    @Transactional
    public void sendVerificationEmail(Profile profile) {
        String raw = issue(profile, AuthToken.Purpose.email_verification,
                Instant.now().plus(VERIFY_TTL_HOURS, ChronoUnit.HOURS));
        String link = "https://" + props.brand().domain() + "/verify-email?token=" + raw;
        email.sendEmail(profile.getEmail(),
                "Confirm your " + props.brand().name() + " email",
                "<p>Welcome to " + props.brand().name() + ".</p>"
                        + "<p><a href=\"" + link + "\">Confirm this email address</a></p>");
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        AuthToken token = consume(rawToken, AuthToken.Purpose.email_verification);
        Profile profile = profiles.findById(token.getUserId())
                .orElseThrow(() -> ApiException.notFound("Account"));
        profile.setEmailVerified(true);
        profiles.save(profile);
    }

    // ---- internals ----

    private String issue(Profile profile, AuthToken.Purpose purpose, Instant expiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        AuthToken token = new AuthToken();
        token.setUserId(profile.getId());
        token.setTokenHash(hash(raw));
        token.setPurpose(purpose);
        token.setExpiresAt(expiresAt);
        tokens.save(token);
        return raw;
    }

    private AuthToken consume(String rawToken, AuthToken.Purpose purpose) {
        AuthToken token = tokens.findByTokenHashAndPurpose(hash(rawToken), purpose)
                .orElseThrow(() -> ApiException.badRequest("This link is invalid or has already been used"));
        if (!token.isUsable()) {
            throw ApiException.badRequest("This link has expired or has already been used");
        }
        token.setUsedAt(Instant.now());
        tokens.save(token);
        return token;
    }

    private static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
