package com.fixbridge.notification;

import com.fixbridge.config.FixBridgeProperties;
import com.fixbridge.contractor.Contractor;
import com.fixbridge.contractor.ContractorRepository;
import com.fixbridge.user.Profile;
import com.fixbridge.user.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Fires transactional notifications on money-loop events (spec §15). Persists every notification to the
 * {@code notifications} table and dispatches via the configured SMS/email senders. Methods are async so
 * a slow/failing provider never blocks the request; failures are logged, not surfaced.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final ProfileRepository profiles;
    private final ContractorRepository contractors;
    private final SmsSender sms;
    private final EmailSender email;
    private final String brand;

    public NotificationService(NotificationRepository notifications, ProfileRepository profiles,
                               ContractorRepository contractors, SmsSender sms, EmailSender email,
                               FixBridgeProperties props) {
        this.notifications = notifications;
        this.profiles = profiles;
        this.contractors = contractors;
        this.sms = sms;
        this.email = email;
        this.brand = props.brand().name();
    }

    @Async
    public void contractorInvited(UUID contractorId, UUID jobId) {
        contractorRecipient(contractorId).ifPresent(r -> send(r, "contractor_invited", jobId,
                null, "New " + brand + " job invitation available — open the app to review and bid.", null));
    }

    @Async
    public void proposalSent(UUID customerId, UUID jobId, long retailCents) {
        customerRecipient(customerId).ifPresent(r -> send(r, "proposal_sent", jobId,
                "Your " + brand + " proposal is ready",
                "Your " + brand + " proposal (" + money(retailCents) + ") is ready to review and approve.",
                "<p>Your " + brand + " proposal for <strong>" + money(retailCents) + "</strong> is ready. "
                        + "Sign in to review and approve.</p>"));
    }

    @Async
    public void changeOrderSent(UUID customerId, UUID jobId, long addedRetailCents) {
        customerRecipient(customerId).ifPresent(r -> send(r, "change_order_sent", jobId,
                "Additional work needs your approval",
                "Additional work (" + money(addedRetailCents) + ") needs your approval before it can continue.",
                "<p>Additional work of <strong>" + money(addedRetailCents) + "</strong> was found on site and "
                        + "needs your approval before it continues.</p>"));
    }

    @Async
    public void workCompleted(UUID customerId, UUID jobId) {
        customerRecipient(customerId).ifPresent(r -> send(r, "work_completed", jobId,
                "Your " + brand + " job is complete",
                "Your " + brand + " job is complete. Sign in to review the completion report.",
                "<p>Your " + brand + " job is complete. Sign in to review the completion report and warranty.</p>"));
    }

    @Async
    public void payoutReleased(UUID contractorId, UUID jobId, long amountCents) {
        contractorRecipient(contractorId).ifPresent(r -> send(r, "payout_released", jobId,
                null, "Your " + brand + " payout of " + money(amountCents) + " has been released.", null));
    }

    // ---- internals ----

    private record Recipient(UUID userId, String email, String phone) {}

    private java.util.Optional<Recipient> customerRecipient(UUID userId) {
        return profiles.findById(userId).map(p -> new Recipient(p.getId(), p.getEmail(), p.getPhone()));
    }

    private java.util.Optional<Recipient> contractorRecipient(UUID contractorId) {
        Contractor c = contractors.findById(contractorId).orElse(null);
        if (c == null) return java.util.Optional.empty();
        Profile owner = profiles.findById(c.getOwnerUserId()).orElse(null);
        String mail = c.getContactEmail() != null ? c.getContactEmail() : owner != null ? owner.getEmail() : null;
        String phone = c.getContactPhone() != null ? c.getContactPhone() : owner != null ? owner.getPhone() : null;
        return java.util.Optional.of(new Recipient(c.getOwnerUserId(), mail, phone));
    }

    /** Sends whichever channels the recipient has contact info for, and records each attempt. */
    private void send(Recipient r, String template, UUID jobId, String emailSubject, String smsBody, String emailHtml) {
        Map<String, Object> payload = Map.of("jobId", String.valueOf(jobId), "template", template);
        if (smsBody != null && r.phone() != null && !r.phone().isBlank()) {
            boolean ok = sms.sendSms(r.phone(), smsBody);
            record(r.userId(), "sms", template, payload, ok);
        }
        if (emailHtml != null && emailSubject != null && r.email() != null && !r.email().isBlank()) {
            boolean ok = email.sendEmail(r.email(), emailSubject, emailHtml);
            record(r.userId(), "email", template, payload, ok);
        }
    }

    private void record(UUID userId, String channel, String template, Map<String, Object> payload, boolean sent) {
        try {
            Notification n = new Notification();
            n.setUserId(userId);
            n.setChannel(channel);
            n.setTemplate(template);
            n.setPayload(payload);
            n.setSentAt(sent ? Instant.now() : null);
            notifications.save(n);
        } catch (Exception e) {
            log.warn("Failed to record notification {}/{}: {}", template, channel, e.getMessage());
        }
    }

    private static String money(long cents) {
        return "$" + BigDecimal.valueOf(cents, 2).toPlainString();
    }
}
