package com.fixbridge.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Stripe webhook receiver. Requirements (spec §8.4): verify the signature from the RAW body, store each
 * event id and process it exactly once (idempotency), and compute every amount server-side.
 *
 * <p>In stub mode (no Stripe secret) the body is accepted as a simplified event so the loop can be
 * driven locally; with a real secret, {@code com.stripe.net.Webhook.constructEvent} verifies it.
 */
@Controller
@RequestMapping("/api/webhooks")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final FixBridgeProperties props;
    private final PaymentService paymentService;
    private final WebhookEventRepository webhookEvents;
    private final ObjectMapper objectMapper;

    public StripeWebhookController(FixBridgeProperties props, PaymentService paymentService,
                                   WebhookEventRepository webhookEvents, ObjectMapper objectMapper) {
        this.props = props;
        this.paymentService = paymentService;
        this.webhookEvents = webhookEvents;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/stripe")
    @ResponseBody
    @Transactional
    public ResponseEntity<String> handle(@RequestBody String rawBody,
                                         @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        StripeEvent event;
        try {
            event = parseAndVerify(rawBody, signature);
        } catch (Exception ex) {
            log.warn("Rejected Stripe webhook: {}", ex.getMessage());
            return ResponseEntity.badRequest().body("invalid signature");
        }

        // Idempotency: process each event id exactly once.
        if (webhookEvents.existsByProviderAndEventId("stripe", event.id())) {
            return ResponseEntity.ok("already processed");
        }
        WebhookEvent record = new WebhookEvent("stripe", event.id(), event.type());

        switch (event.type()) {
            case "checkout.session.completed" -> paymentService.handlePaidCheckout(event.objectId());
            case "payment_intent.succeeded" -> log.info("payment_intent.succeeded {}", event.objectId());
            case "account.updated", "transfer.created", "transfer.reversed",
                 "payout.paid", "payout.failed", "charge.refunded", "charge.dispute.created" ->
                    log.info("Recorded Stripe event {} ({})", event.type(), event.objectId());
            default -> log.info("Unhandled Stripe event type {}", event.type());
        }

        record.setProcessedAt(java.time.Instant.now());
        webhookEvents.save(record);
        return ResponseEntity.ok("ok");
    }

    private record StripeEvent(String id, String type, String objectId) {}

    private StripeEvent parseAndVerify(String rawBody, String signature) throws Exception {
        boolean stub = props.ai().stubMode() || props.stripe().webhookSecret() == null
                || props.stripe().webhookSecret().isBlank();
        if (!stub) {
            // Real verification against the raw body.
            com.stripe.model.Event event = com.stripe.net.Webhook.constructEvent(
                    rawBody, signature, props.stripe().webhookSecret());
            JsonNode root = objectMapper.readTree(rawBody);
            String objectId = root.path("data").path("object").path("id").asText(null);
            return new StripeEvent(event.getId(), event.getType(), objectId);
        }
        // Stub: trust the local body { "id", "type", "data": { "object": { "id" } } }.
        JsonNode root = objectMapper.readTree(rawBody);
        String id = root.path("id").asText(null);
        String type = root.path("type").asText(null);
        String objectId = root.path("data").path("object").path("id").asText(null);
        if (id == null || type == null) {
            throw new IllegalArgumentException("Missing id/type in stub event");
        }
        return new StripeEvent(id, type, objectId);
    }
}
