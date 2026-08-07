package com.fixbridge.notification;

import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/** Live email via the Resend API. Active only when {@code fixbridge.notifications.stub-mode=false}. */
@Component
@ConditionalOnProperty(prefix = "fixbridge.notifications", name = "stub-mode", havingValue = "false")
public class ResendEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailSender.class);

    private final WebClient client;
    private final String fromEmail;

    public ResendEmailSender(FixBridgeProperties props) {
        FixBridgeProperties.Resend r = props.resend();
        this.fromEmail = r.fromEmail();
        this.client = WebClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader("Authorization", "Bearer " + r.apiKey())
                .build();
    }

    @Override
    public boolean sendEmail(String toEmail, String subject, String htmlBody) {
        try {
            client.post().uri("/emails")
                    .bodyValue(Map.of(
                            "from", fromEmail,
                            "to", List.of(toEmail),
                            "subject", subject,
                            "html", htmlBody))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return true;
        } catch (Exception e) {
            log.warn("Resend email send failed: {}", e.getMessage());
            return false;
        }
    }
}
