package com.fixbridge.auth;

import com.fixbridge.auth.dto.AuthDtos;
import com.fixbridge.auth.otp.OtpCode;
import com.fixbridge.auth.otp.OtpService;
import com.fixbridge.common.enums.UserRole;
import com.fixbridge.common.error.ApiException;
import com.fixbridge.user.Profile;
import com.fixbridge.user.ProfileRepository;
import com.fixbridge.user.UserRoleEntity;
import com.fixbridge.user.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The "Continue" decision: after a code proves a phone or inbox, does this person sign in or get
 * onboarded? The wrong answer in either direction is an account-takeover or a duplicate account,
 * so the routing itself is what these tests pin down.
 */
class OtpAuthFlowTest {

    private final ProfileRepository profiles = mock(ProfileRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final AuthTokenService authTokens = mock(AuthTokenService.class);
    private final OtpService otp = mock(OtpService.class);

    private AuthService service;

    private static final String PHONE_RAW = "(212) 555-1234";
    private static final String PHONE_E164 = "+12125551234";

    @BeforeEach
    void setUp() {
        service = new AuthService(profiles, roles, new BCryptPasswordEncoder(4), jwt, authTokens, otp);
        when(jwt.generateAccessToken(any(), anyString(), any())).thenReturn("access");
        when(jwt.generateRefreshToken(any())).thenReturn("refresh");
        when(profiles.save(any())).thenAnswer(i -> {
            Profile p = i.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
    }

    private Profile existing(String email, String phone) {
        Profile p = new Profile();
        p.setId(UUID.randomUUID());
        p.setEmail(email);
        p.setPhone(phone);
        p.setPasswordHash("$2a$04$whatever");
        return p;
    }

    // ---- Normalisation: many typings, one identity ----

    @Test
    void phoneFormattingVariantsAllReachTheSameDestination() {
        for (String typed : List.of("(212) 555-1234", "212-555-1234", "2125551234", "+1 212 555 1234", "1-212-555-1234")) {
            assertThat(AuthService.normalizeDestination(OtpCode.Channel.sms, typed))
                    .as("normalising %s", typed)
                    .isEqualTo(PHONE_E164);
        }
    }

    @Test
    void anInvalidPhoneIsARequestErrorBeforeAnySmsIsSent() {
        assertThatThrownBy(() -> service.otpSend(new AuthDtos.OtpSendRequest("sms", "12345")))
                .isInstanceOf(ApiException.class);
        verify(otp, never()).sendCode(any(), anyString());
    }

    @Test
    void emailsAreLowerCasedSoCaseCannotForkAccounts() {
        assertThat(AuthService.normalizeDestination(OtpCode.Channel.email, "Pat@Example.COM"))
                .isEqualTo("pat@example.com");
    }

    // ---- The routing decision ----

    @Test
    void aKnownPhoneSignsInWithoutAnyTicket() {
        when(profiles.findFirstByPhoneOrderByCreatedAtAsc(PHONE_E164))
                .thenReturn(Optional.of(existing("pat@example.com", PHONE_E164)));
        when(roles.findByUserId(any())).thenReturn(List.of());

        var res = service.otpVerify(new AuthDtos.OtpVerifyRequest("sms", PHONE_RAW, "123456"));

        verify(otp).verify(PHONE_E164, "123456");
        assertThat(res.newUser()).isFalse();
        assertThat(res.tokens()).isNotNull();
        assertThat(res.signupTicket()).isNull();
    }

    @Test
    void anUnknownPhoneGetsATicketAndNoTokens() {
        when(profiles.findFirstByPhoneOrderByCreatedAtAsc(PHONE_E164)).thenReturn(Optional.empty());
        when(otp.issueSignupTicket(OtpCode.Channel.sms, PHONE_E164)).thenReturn("id.secret");

        var res = service.otpVerify(new AuthDtos.OtpVerifyRequest("sms", PHONE_RAW, "123456"));

        assertThat(res.newUser()).isTrue();
        assertThat(res.tokens()).isNull();
        assertThat(res.signupTicket()).isEqualTo("id.secret");
    }

    @Test
    void aFailedCodeNeverReachesTheAccountLookup() {
        doThrow(ApiException.badRequest("That code doesn't look right. Try again."))
                .when(otp).verify(anyString(), anyString());

        assertThatThrownBy(() -> service.otpVerify(new AuthDtos.OtpVerifyRequest("sms", PHONE_RAW, "000000")))
                .isInstanceOf(ApiException.class);
        verify(profiles, never()).findFirstByPhoneOrderByCreatedAtAsc(anyString());
    }

    @Test
    void provingTheInboxMarksTheEmailVerified() {
        Profile p = existing("pat@example.com", null);
        p.setEmailVerified(false);
        when(profiles.findByEmailIgnoreCase("pat@example.com")).thenReturn(Optional.of(p));
        when(roles.findByUserId(any())).thenReturn(List.of());

        service.otpVerify(new AuthDtos.OtpVerifyRequest("email", "Pat@Example.com", "123456"));

        assertThat(p.isEmailVerified()).isTrue();
    }

    // ---- Onboarding completion ----

    private OtpCode ticketFor(OtpCode.Channel channel, String destination) {
        OtpCode t = new OtpCode();
        t.setChannel(channel);
        t.setDestination(destination);
        t.setPurpose(OtpCode.Purpose.signup);
        return t;
    }

    @Test
    void aPhoneProvedNewcomerGetsACustomerAccountWithThatPhone() {
        when(otp.consumeSignupTicket("t")).thenReturn(ticketFor(OtpCode.Channel.sms, PHONE_E164));
        when(profiles.existsByEmailIgnoreCase("pat@example.com")).thenReturn(false);

        var res = service.otpComplete(new AuthDtos.OtpCompleteRequest("t", "Pat Doe", "pat@example.com"));

        ArgumentCaptor<Profile> saved = ArgumentCaptor.forClass(Profile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo(PHONE_E164);
        assertThat(saved.getValue().isEmailVerified()).isFalse(); // the inbox was never proved
        ArgumentCaptor<UserRoleEntity> role = ArgumentCaptor.forClass(UserRoleEntity.class);
        verify(roles).save(role.capture());
        assertThat(role.getValue().getRole()).isEqualTo(UserRole.customer);
        assertThat(res.user().roles()).containsExactly(UserRole.customer);
    }

    @Test
    void anEmailProvedNewcomerNeedsNoSecondEmailAndIsVerified() {
        when(otp.consumeSignupTicket("t")).thenReturn(ticketFor(OtpCode.Channel.email, "pat@example.com"));
        when(profiles.existsByEmailIgnoreCase("pat@example.com")).thenReturn(false);

        service.otpComplete(new AuthDtos.OtpCompleteRequest("t", "Pat Doe", null));

        ArgumentCaptor<Profile> saved = ArgumentCaptor.forClass(Profile.class);
        verify(profiles).save(saved.capture());
        assertThat(saved.getValue().isEmailVerified()).isTrue();
        verify(authTokens, never()).sendVerificationEmail(any());
    }

    @Test
    void aPhoneTicketCannotClaimSomebodyElsesEmail() {
        // Signing into the existing account on phone-proof alone would be a takeover; creating a
        // duplicate would strand it. The only safe answer is neither.
        when(otp.consumeSignupTicket("t")).thenReturn(ticketFor(OtpCode.Channel.sms, PHONE_E164));
        when(profiles.existsByEmailIgnoreCase("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.otpComplete(
                new AuthDtos.OtpCompleteRequest("t", "Pat Doe", "taken@example.com")))
                .isInstanceOf(ApiException.class);
        verify(profiles, never()).save(any());
    }

    @Test
    void thePasswordlessAccountCannotBeEnteredWithAnyPassword() {
        when(otp.consumeSignupTicket("t")).thenReturn(ticketFor(OtpCode.Channel.email, "pat@example.com"));
        when(profiles.existsByEmailIgnoreCase("pat@example.com")).thenReturn(false);

        service.otpComplete(new AuthDtos.OtpCompleteRequest("t", "Pat Doe", null));

        ArgumentCaptor<Profile> saved = ArgumentCaptor.forClass(Profile.class);
        verify(profiles).save(saved.capture());
        // A real BCrypt hash of a random secret — not empty, not a sentinel a guesser could hit.
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2");
        assertThat(new BCryptPasswordEncoder(4).matches("", saved.getValue().getPasswordHash())).isFalse();
        assertThat(new BCryptPasswordEncoder(4).matches("password", saved.getValue().getPasswordHash())).isFalse();
    }
}
