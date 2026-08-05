package com.fixbridge.auth;

import com.fixbridge.common.enums.UserRole;
import com.fixbridge.config.FixBridgeProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Issues and verifies JWT access and refresh tokens (HS256). */
@Service
public class JwtService {

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTtlMinutes;
    private final long refreshTtlDays;

    public JwtService(FixBridgeProperties props) {
        this.key = Keys.hmacShaKeyFor(props.security().jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.accessTtlMinutes = props.security().accessTokenTtlMinutes();
        this.refreshTtlDays = props.security().refreshTokenTtlDays();
    }

    public String generateAccessToken(UUID userId, String email, List<UserRole> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .claim(CLAIM_ROLES, roles.stream().map(Enum::name).toList())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plus(refreshTtlDays, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    @SuppressWarnings("unchecked")
    public AuthUser toAuthUser(Claims claims) {
        UUID id = UUID.fromString(claims.getSubject());
        String email = claims.get("email", String.class);
        List<String> roleNames = claims.get(CLAIM_ROLES, List.class);
        List<UserRole> roles = roleNames == null ? List.of()
                : roleNames.stream().map(UserRole::valueOf).toList();
        return new AuthUser(id, email, roles);
    }
}
