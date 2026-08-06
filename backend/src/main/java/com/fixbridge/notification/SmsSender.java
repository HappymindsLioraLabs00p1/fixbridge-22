package com.fixbridge.notification;

/** Sends transactional SMS (Twilio in live mode). Returns true if accepted by the provider. */
public interface SmsSender {
    boolean sendSms(String toNumber, String body);
}
