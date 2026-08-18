package com.fixbridge.auth.otp;

import com.fixbridge.common.error.ApiException;
import com.fixbridge.notification.EmailSender;
import com.fixbridge.notification.SmsSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one-time-code rules ARE the security model, so each limit gets its own test: a code works
 * once, dies at expiry, dies after five wrong guesses, and cannot be re-requested without limit.
 *
 * <p>The delivered SMS/email body is captured so tests can use the real code — the service itself
 * never returns it, which is rather the point.
 */
class OtpServiceTest {

    private final List<OtpCode> stored = new ArrayList<>();
    private final List<String> sentBodies = new ArrayList<>();

    private OtpService service;

    private static final String PHONE = "+12125551234";

    @BeforeEach
    void setUp() {
        OtpCodeRepository repo = mock(OtpCodeRepository.class);
        when(repo.save(any())).thenAnswer(i -> {
            OtpCode c = i.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            if (c.getCreatedAt() == null) c.setCreatedAt(Instant.now());
            if (!stored.contains(c)) stored.add(c);
            return c;
        });
        when(repo.findFirstByDestinationAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(anyString(), any()))
                .thenAnswer(i -> stored.stream()
                        .filter(c -> c.getDestination().equals(i.getArgument(0))
                                && c.getPurpose() == i.getArgument(1)
                                && c.getConsumedAt() == null)
                        .max(Comparator.comparing(OtpCode::getCreatedAt)));
        when(repo.countByDestinationAndPurposeAndCreatedAtAfter(anyString(), any(), any()))
                .thenAnswer(i -> stored.stream()
                        .filter(c -> c.getDestination().equals(i.getArgument(0))
                                && c.getPurpose() == i.getArgument(1)
                                && c.getCreatedAt().isAfter(i.getArgument(2)))
                        .count());
        when(repo.findById(any())).thenAnswer(i ->
                stored.stream().filter(c -> c.getId().equals(i.getArgument(0))).findFirst());

        SmsSender sms = mock(SmsSender.class);
        when(sms.sendSms(anyString(), anyString())).thenAnswer(i -> sentBodies.add(i.getArgument(1)));
        EmailSender email = mock(EmailSender.class);
        when(email.sendEmail(anyString(), anyString(), anyString()))
                .thenAnswer(i -> sentBodies.add(i.getArgument(2)));

        service = new OtpService(repo, new BCryptPasswordEncoder(4), sms, email);
    }

    /** The code as the customer would see it — fished out of the delivered message. */
    private String lastDeliveredCode() {
        Matcher m = Pattern.compile("(\\d{6})").matcher(sentBodies.get(sentBodies.size() - 1));
        assertThat(m.find()).as("a 6-digit code appears in the delivered message").isTrue();
        return m.group(1);
    }

    // ---- The happy path, once ----

    @Test
    void theDeliveredCodeVerifiesExactlyOnce() {
        service.sendCode(OtpCode.Channel.sms, PHONE);
        String code = lastDeliveredCode();

        assertThatCode(() -> service.verify(PHONE, code)).doesNotThrowAnyException();
        // Same code again: it was consumed. Signing in twice needs two codes.
        assertThatThrownBy(() -> service.verify(PHONE, code)).isInstanceOf(ApiException.class);
    }

    @Test
    void theRawCodeIsNeverStoredOnlyItsHash() {
        service.sendCode(OtpCode.Channel.sms, PHONE);
        String code = lastDeliveredCode();

        assertThat(stored.get(0).getCodeHash()).doesNotContain(code).startsWith("$2");
    }

    // ---- What must fail ----

    @Test
    void aWrongCodeFailsAndCountsAsAnAttempt() {
        service.sendCode(OtpCode.Channel.sms, PHONE);

        assertThatThrownBy(() -> service.verify(PHONE, "000000")).isInstanceOf(ApiException.class);
        assertThat(stored.get(0).getAttempts()).isEqualTo(1);
    }

    @Test
    void fiveWrongGuessesKillTheCodeEvenForTheRightAnswer() {
        // The attempt cap is what makes six digits enough entropy.
        service.sendCode(OtpCode.Channel.sms, PHONE);
        String code = lastDeliveredCode();

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verify(PHONE, "999999")).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> service.verify(PHONE, code))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("new code");
    }

    @Test
    void anExpiredCodeIsRefusedWithAMessageSayingToRequestANewOne() {
        service.sendCode(OtpCode.Channel.sms, PHONE);
        String code = lastDeliveredCode();
        stored.get(0).setExpiresAt(Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> service.verify(PHONE, code))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void verifyingWithNoCodeOutstandingFailsSafely() {
        assertThatThrownBy(() -> service.verify(PHONE, "123456")).isInstanceOf(ApiException.class);
    }

    @Test
    void aFourthSendInsideTheWindowIsRefused() {
        for (int i = 0; i < 3; i++) service.sendCode(OtpCode.Channel.sms, PHONE);

        assertThatThrownBy(() -> service.sendCode(OtpCode.Channel.sms, PHONE))
                .isInstanceOf(ApiException.class);
        assertThat(sentBodies).hasSize(3);
    }

    @Test
    void onlyTheLatestCodeCounts() {
        // Resend replaces: the older code must not stay alive as a second guessable secret.
        service.sendCode(OtpCode.Channel.sms, PHONE);
        String first = lastDeliveredCode();
        service.sendCode(OtpCode.Channel.sms, PHONE);
        String second = lastDeliveredCode();

        if (!first.equals(second)) {
            assertThatThrownBy(() -> service.verify(PHONE, first)).isInstanceOf(ApiException.class);
        }
        assertThatCode(() -> service.verify(PHONE, second)).doesNotThrowAnyException();
    }

    // ---- Signup tickets ----

    @Test
    void aTicketRedeemsOnceAndCarriesTheProvedDestination() {
        String ticket = service.issueSignupTicket(OtpCode.Channel.sms, PHONE);

        OtpCode redeemed = service.consumeSignupTicket(ticket);
        assertThat(redeemed.getDestination()).isEqualTo(PHONE);
        assertThat(redeemed.getChannel()).isEqualTo(OtpCode.Channel.sms);

        assertThatThrownBy(() -> service.consumeSignupTicket(ticket)).isInstanceOf(ApiException.class);
    }

    @Test
    void aTamperedTicketIsRefused() {
        String ticket = service.issueSignupTicket(OtpCode.Channel.sms, PHONE);
        String forged = ticket.substring(0, ticket.length() - 4) + "AAAA";

        assertThatThrownBy(() -> service.consumeSignupTicket(forged)).isInstanceOf(ApiException.class);
        // And garbage shapes fail as requests, not crashes.
        assertThatThrownBy(() -> service.consumeSignupTicket("not-a-ticket")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.consumeSignupTicket(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void aLoginCodeCannotBeRedeemedAsASignupTicket() {
        service.sendCode(OtpCode.Channel.sms, PHONE);
        OtpCode codeRow = stored.get(0);

        assertThatThrownBy(() -> service.consumeSignupTicket(codeRow.getId() + "." + lastDeliveredCode()))
                .isInstanceOf(ApiException.class);
    }
}
