package com.fixbridge.auth;

import com.fixbridge.auth.dto.AuthDtos;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.user.Profile;
import com.fixbridge.user.ProfileRepository;
import com.fixbridge.user.UserRoleEntity;
import com.fixbridge.user.UserRoleRepository;
import io.jsonwebtoken.Claims;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private static final Set<UserRole> SELF_REGISTERABLE =
            Set.of(UserRole.customer, UserRole.contractor, UserRole.landlord, UserRole.agent);

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);

    private final ProfileRepository profiles;
    private final UserRoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthTokenService authTokenService;
    private final com.fixbridge.auth.otp.OtpService otpService;

    public AuthService(ProfileRepository profiles, UserRoleRepository roles,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       AuthTokenService authTokenService,
                       com.fixbridge.auth.otp.OtpService otpService) {
        this.profiles = profiles;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authTokenService = authTokenService;
        this.otpService = otpService;
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest req) {
        if (profiles.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("An account with this email already exists");
        }
        UserRole role = req.role() == null ? UserRole.customer : req.role();
        if (!SELF_REGISTERABLE.contains(role)) {
            throw ApiException.badRequest("This role cannot be self-registered");
        }

        Profile profile = new Profile();
        profile.setEmail(req.email());
        profile.setPasswordHash(passwordEncoder.encode(req.password()));
        profile.setFullName(req.fullName());
        profile = profiles.save(profile);

        roles.save(new UserRoleEntity(profile.getId(), role));

        // Send the confirmation link. A delivery failure must never block sign-up — the account is
        // already usable, and the link can be re-requested.
        try {
            authTokenService.sendVerificationEmail(profile);
        } catch (Exception e) {
            log.warn("Could not send the verification email to {}: {}", profile.getId(), e.getMessage());
        }
        return issueTokens(profile, List.of(role));
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest req) {
        Profile profile = profiles.findByEmailIgnoreCase(req.email())
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"));
        if (!passwordEncoder.matches(req.password(), profile.getPasswordHash())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "Invalid email or password");
        }
        return issueTokens(profile, rolesOf(profile.getId()));
    }

    @Transactional(readOnly = true)
    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest req) {
        Claims claims;
        try {
            claims = jwtService.parse(req.refreshToken());
        } catch (Exception ex) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        if (!jwtService.isRefreshToken(claims)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Not a refresh token");
        }
        UUID userId = UUID.fromString(claims.getSubject());
        Profile profile = profiles.findById(userId).orElseThrow(() -> ApiException.notFound("Account"));
        return issueTokens(profile, rolesOf(userId));
    }

    // ---- Passwordless ("Continue" with phone or email) ----
    //
    // Login and signup are one flow on purpose: the person types where to reach them, proves they
    // control it, and only then does the system decide whether that's a returning customer or a new
    // one. Nobody is asked "do you already have an account?" — the database knows.

    /** Send a one-time code. Deliberately does not reveal whether an account exists. */
    public void otpSend(AuthDtos.OtpSendRequest req) {
        var channel = parseChannel(req.channel());
        String destination = normalizeDestination(channel, req.destination());
        otpService.sendCode(channel, destination);
    }

    /**
     * Check the code. A match signs in the matching account, or — when there is none — returns a
     * signup ticket so onboarding can finish the job without re-proving the phone or inbox.
     */
    @Transactional
    public AuthDtos.OtpVerifyResponse otpVerify(AuthDtos.OtpVerifyRequest req) {
        var channel = parseChannel(req.channel());
        String destination = normalizeDestination(channel, req.destination());
        otpService.verify(destination, req.code());

        var existing = switch (channel) {
            case sms -> profiles.findFirstByPhoneOrderByCreatedAtAsc(destination);
            case email -> profiles.findByEmailIgnoreCase(destination);
        };
        if (existing.isPresent()) {
            Profile profile = existing.get();
            if (channel == com.fixbridge.auth.otp.OtpCode.Channel.email && !profile.isEmailVerified()) {
                // They just proved control of the inbox — stronger evidence than a clicked link.
                profile.setEmailVerified(true);
                profiles.save(profile);
            }
            return new AuthDtos.OtpVerifyResponse(issueTokens(profile, rolesOf(profile.getId())), null, false);
        }
        return new AuthDtos.OtpVerifyResponse(null,
                otpService.issueSignupTicket(channel, destination), true);
    }

    /**
     * Create the account a verified newcomer was promised. The ticket carries which destination was
     * proved; the request brings the name (and the email, when the proof was a phone — accounts are
     * keyed by email and the schema requires one).
     *
     * <p>The account is passwordless: the stored hash is of a random 256-bit secret nobody knows, so
     * password login simply fails until the customer sets one via the reset flow. That keeps
     * {@code password_hash NOT NULL} honest without a schema change.
     */
    @Transactional
    public AuthDtos.TokenResponse otpComplete(AuthDtos.OtpCompleteRequest req) {
        var ticket = otpService.consumeSignupTicket(req.signupTicket());

        String email;
        String phone = null;
        boolean emailVerified;
        if (ticket.getChannel() == com.fixbridge.auth.otp.OtpCode.Channel.sms) {
            phone = ticket.getDestination();
            if (req.email() == null || !req.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw ApiException.badRequest("Please enter a valid email address.");
            }
            email = req.email().trim();
            emailVerified = false; // the phone was proved; the inbox was not
        } else {
            email = ticket.getDestination();
            emailVerified = true;
        }
        if (profiles.existsByEmailIgnoreCase(email)) {
            // The phone was new but the email already has an account. Creating a second would strand
            // one of them; signing into the existing one on phone-proof alone would let a phone claim
            // someone else's email. Stop and route them to a proof of that inbox instead.
            throw ApiException.conflict(
                    "That email already has a FixBridge account — continue with email to sign into it.");
        }

        byte[] noPassword = new byte[32];
        new java.security.SecureRandom().nextBytes(noPassword);

        Profile profile = new Profile();
        profile.setEmail(email);
        profile.setPasswordHash(passwordEncoder.encode(
                java.util.Base64.getEncoder().encodeToString(noPassword)));
        profile.setFullName(req.fullName().trim());
        profile.setPhone(phone);
        profile.setEmailVerified(emailVerified);
        profile = profiles.save(profile);
        roles.save(new UserRoleEntity(profile.getId(), UserRole.customer));

        if (!emailVerified) {
            try {
                authTokenService.sendVerificationEmail(profile);
            } catch (Exception e) {
                log.warn("Could not send the verification email to {}: {}", profile.getId(), e.getMessage());
            }
        }
        return issueTokens(profile, List.of(UserRole.customer));
    }

    private static com.fixbridge.auth.otp.OtpCode.Channel parseChannel(String raw) {
        try {
            return com.fixbridge.auth.otp.OtpCode.Channel.valueOf(raw.toLowerCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unsupported sign-in method");
        }
    }

    /**
     * Phones become E.164 (+1XXXXXXXXXX — US numbers, matching the product's NYC/Long Island market);
     * emails become lower-case. Normalising before storage AND lookup is what lets "(212) 555-1234"
     * and "2125551234" reach the same account.
     */
    static String normalizeDestination(com.fixbridge.auth.otp.OtpCode.Channel channel, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (channel == com.fixbridge.auth.otp.OtpCode.Channel.email) {
            if (!value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw ApiException.badRequest("Please enter a valid email address.");
            }
            return value.toLowerCase(java.util.Locale.ROOT);
        }
        String digits = value.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        if (digits.length() != 10) {
            throw ApiException.badRequest("Please enter a valid phone number.");
        }
        return "+1" + digits;
    }

    private List<UserRole> rolesOf(UUID userId) {
        return roles.findByUserId(userId).stream().map(UserRoleEntity::getRole).toList();
    }

    private AuthDtos.TokenResponse issueTokens(Profile profile, List<UserRole> userRoles) {
        String access = jwtService.generateAccessToken(profile.getId(), profile.getEmail(), userRoles);
        String refresh = jwtService.generateRefreshToken(profile.getId());
        AuthDtos.UserView view = new AuthDtos.UserView(
                profile.getId(), profile.getEmail(), profile.getFullName(), userRoles);
        return AuthDtos.TokenResponse.bearer(access, refresh, view);
    }
}
