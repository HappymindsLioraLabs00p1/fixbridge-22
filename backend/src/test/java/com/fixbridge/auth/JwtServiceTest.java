package com.fixbridge.auth;

import com.fixbridge.common.enums.UserRole;
import com.fixbridge.support.TestFixtures;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwt = new JwtService(TestFixtures.props());

    @Test
    void accessTokenRoundTripsIdentityAndRoles() {
        UUID id = UUID.randomUUID();
        String token = jwt.generateAccessToken(id, "a@b.com", List.of(UserRole.customer, UserRole.admin));

        Claims claims = jwt.parse(token);
        assertThat(jwt.isRefreshToken(claims)).isFalse();

        AuthUser user = jwt.toAuthUser(claims);
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.email()).isEqualTo("a@b.com");
        assertThat(user.roles()).containsExactlyInAnyOrder(UserRole.customer, UserRole.admin);
    }

    @Test
    void refreshTokenIsDistinguishedFromAccessToken() {
        Claims claims = jwt.parse(jwt.generateRefreshToken(UUID.randomUUID()));
        assertThat(jwt.isRefreshToken(claims)).isTrue();
    }
}
