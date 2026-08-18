package com.fixbridge.auth.otp;

import com.fixbridge.common.error.ApiException;
import com.fixbridge.notification.EmailSender;
import com.fixbridge.notification.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * One-time codes for passwordless sign-in, and the signup tickets that follow them.
 *
 * <p>The numbers here are the security model, so they are worth stating: a code is 6 digits
 * (1,000,000 possibilities), lives {@link #CODE_TTL 10 minutes}, dies after
 * {@link #MAX_ATTEMPTS 5} wrong guesses, and at most {@link #MAX_SENDS_PER_WINDOW 3} codes can be
 * requested per destination per {@link #SEND_WINDOW 10 minutes}. Guessing odds are therefore
 * 5 in 1,000,000 per code — the attempt cap is what makes 6 digits enough.
 *
 * <p>Codes are hashed with the same {@link PasswordEncoder} as passwords. The raw value exists in
 * the SMS or email and nowhere else durable.
 *
 * <p>A <b>signup ticket</b> is issued when a code verifies but no account matches: it carries
 * "this person proved control of that phone/inbox" into onboarding without holding the account
 * open-ended. Tickets are single-use, 15-minute, and shaped {@code <rowId>.<secret>} so the row can
 * be found by id and the secret checked against its hash — BCrypt hashes are salted, so the hash
 * itself cannot be a lookup key.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration TICKET_TTL = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_SENDS_PER_WINDOW = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(10);

    private final OtpCodeRepository codes;
    private final PasswordEncoder encoder;
    private final SmsSender sms;
    private final EmailSender email;
    private final SecureRandom random = new SecureRandom();

    public OtpService(OtpCodeRepository codes, PasswordEncoder encoder,
                      SmsSender sms, EmailSender email) {
        this.codes = codes;
        this.encoder = encoder;
        this.sms = sms;
        this.email = email;
    }

    /** Generate, store (hashed) and deliver a code. The raw code never leaves this method's stack. */
    @Transactional
    public void sendCode(OtpCode.Channel channel, String destination) {
        long recent = codes.countByDestinationAndPurposeAndCreatedAtAfter(
                destination, OtpCode.Purpose.login, Instant.now().minus(SEND_WINDOW));
        if (recent >= MAX_SENDS_PER_WINDOW) {
            // A resend hammer is either a delivery problem or someone probing. Either way the fix
            // is not another code.
            throw ApiException.conflict("Please wait a little before requesting another code.");
        }

        String code = String.format("%06d", random.nextInt(1_000_000));

        OtpCode row = new OtpCode();
        row.setDestination(destination);
        row.setChannel(channel);
        row.setPurpose(OtpCode.Purpose.login);
        row.setCodeHash(encoder.encode(code));
        row.setExpiresAt(Instant.now().plus(CODE_TTL));
        codes.save(row);

        boolean accepted = switch (channel) {
            case sms -> sms.sendSms(destination,
                    "Your FixBridge code is " + code + ". It expires in 10 minutes. "
                            + "Never share it — FixBridge will not ask for it.");
            case email -> email.sendEmail(destination, "Your FixBridge sign-in code",
                    "<p>Your FixBridge sign-in code is <strong>" + code + "</strong>.</p>"
                            + "<p>It expires in 10 minutes. If you didn't request it, ignore this email.</p>");
        };
        if (!accepted) {
            // The row stays: the provider may retry-deliver, and the customer can hit resend.
            log.warn("OTP delivery to a {} destination was not accepted by the provider", channel);
        }
    }

    /**
     * Check a code. Consumes it on success — a code signs someone in exactly once.
     *
     * <p>Every failure path throws with a message fit for the customer; the distinction that matters
     * to them is "mistyped" (try again) versus "gone" (request a new one).
     */
    @Transactional
    public void verify(String destination, String code) {
        OtpCode row = codes
                .findFirstByDestinationAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
                        destination, OtpCode.Purpose.login)
                .orElseThrow(() -> ApiException.badRequest("This code has expired. Request a new code."));

        if (row.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("This code has expired. Request a new code.");
        }
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            throw ApiException.badRequest("Too many tries. Request a new code.");
        }
        if (code == null || !encoder.matches(code.trim(), row.getCodeHash())) {
            row.setAttempts(row.getAttempts() + 1);
            codes.save(row);
            throw ApiException.badRequest("That code doesn't look right. Try again.");
        }

        row.setConsumedAt(Instant.now());
        codes.save(row);
    }

    /** Mint a single-use onboarding ticket proving control of {@code destination}. Returns the raw ticket. */
    @Transactional
    public String issueSignupTicket(OtpCode.Channel channel, String destination) {
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        OtpCode row = new OtpCode();
        row.setDestination(destination);
        row.setChannel(channel);
        row.setPurpose(OtpCode.Purpose.signup);
        row.setCodeHash(encoder.encode(secret));
        row.setExpiresAt(Instant.now().plus(TICKET_TTL));
        row = codes.save(row);

        return row.getId() + "." + secret;
    }

    /**
     * Redeem a ticket, returning the verified destination it was issued for. Single-use: the caller
     * is about to create an account on the strength of it.
     */
    @Transactional
    public OtpCode consumeSignupTicket(String ticket) {
        ApiException invalid = ApiException.badRequest("Your session has expired — please verify again.");

        if (ticket == null || ticket.indexOf('.') <= 0) throw invalid;
        String idPart = ticket.substring(0, ticket.indexOf('.'));
        String secret = ticket.substring(ticket.indexOf('.') + 1);

        UUID id;
        try {
            id = UUID.fromString(idPart);
        } catch (IllegalArgumentException e) {
            throw invalid;
        }

        OtpCode row = codes.findById(id).orElseThrow(() -> invalid);
        if (row.getPurpose() != OtpCode.Purpose.signup
                || row.getConsumedAt() != null
                || row.getExpiresAt().isBefore(Instant.now())
                || !encoder.matches(secret, row.getCodeHash())) {
            throw invalid;
        }

        row.setConsumedAt(Instant.now());
        codes.save(row);
        return row;
    }
}
