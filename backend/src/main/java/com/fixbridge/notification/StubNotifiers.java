package com.fixbridge.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic stub SMS + email senders for the frontend-first phase (no Twilio/Resend keys). They log
 * and report success so the notification path is exercised end-to-end locally. Replaced by
 * {@code TwilioSmsSender}/{@code ResendEmailSender} when {@code fixbridge.ai.stub-mode=false}.
 */
public final class StubNotifiers {

    private StubNotifiers() {}

    @Component
    @ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "true", matchIfMissing = true)
    public static class StubSmsSender implements SmsSender {
        private static final Logger log = LoggerFactory.getLogger(StubSmsSender.class);

        @Override
        public boolean sendSms(String toNumber, String body) {
            log.info("[stub-sms] to={} body={}", toNumber, body);
            return true;
        }
    }

    @Component
    @ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "true", matchIfMissing = true)
    public static class StubEmailSender implements EmailSender {
        private static final Logger log = LoggerFactory.getLogger(StubEmailSender.class);

        @Override
        public boolean sendEmail(String toEmail, String subject, String htmlBody) {
            // The body is logged deliberately: in stub mode no mail is actually delivered, so this is
            // the only way to follow a password-reset or verification link locally. Stub mode is for
            // local/demo use only — live mode uses ResendEmailSender and logs nothing sensitive.
            log.info("[stub-email] to={} subject={}\n{}", toEmail, subject, htmlBody);
            return true;
        }
    }
}
