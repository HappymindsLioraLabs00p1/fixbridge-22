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

    private final ProfileRepository profiles;
    private final UserRoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(ProfileRepository profiles, UserRoleRepository roles,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.profiles = profiles;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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
