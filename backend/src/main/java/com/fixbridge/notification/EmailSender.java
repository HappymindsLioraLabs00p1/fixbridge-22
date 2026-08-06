package com.fixbridge.notification;

/** Sends transactional email (Resend in live mode). Returns true if accepted by the provider. */
public interface EmailSender {
    boolean sendEmail(String toEmail, String subject, String htmlBody);
}
