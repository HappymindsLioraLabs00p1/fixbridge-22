package com.fixbridge.notification;

import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/** Live SMS via the Twilio REST API. Active only when {@code fixbridge.notifications.stub-mode=false}. */
@Component
@ConditionalOnProperty(prefix = "fixbridge.notifications", name = "stub-mode", havingValue = "false")
public class TwilioSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsSender.class);

    private final WebClient client;
    private final String fromNumber;

    public TwilioSmsSender(FixBridgeProperties props) {
        FixBridgeProperties.Twilio t = props.twilio();
        this.fromNumber = t.fromNumber();
        this.client = WebClient.builder()
                .baseUrl("https://api.twilio.com/2010-04-01/Accounts/" + t.accountSid())
                .defaultHeaders(h -> h.setBasicAuth(t.accountSid(), t.authToken()))
                .build();
    }

    @Override
    public boolean sendSms(String toNumber, String body) {
        try {
            client.post().uri("/Messages.json")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("To", toNumber).with("From", fromNumber).with("Body", body))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Twilio SMS send failed: {}", e.getMessage());
            return false;
        }
    }
}
