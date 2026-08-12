package com.fixbridge.ai;

import com.fixbridge.config.FixBridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;

/**
 * Reports, at startup, whether the AI service is actually reachable and the credentials work.
 *
 * <p>Without this every misconfiguration looks identical from outside: the customer sees "I'm
 * having trouble thinking right now" whether the URL is wrong, the token is stale, the service is
 * asleep, or the network is blocked. Diagnosing that from the outside meant guessing, and a wrong
 * URL took an afternoon to find because nothing said which of those four it was.
 *
 * <p>Deliberately logs booleans and a host, never the token. Anyone who can read the log should be
 * able to fix the problem without being handed a credential.
 *
 * <p>Never fails startup. An AI service that is down must not stop the rest of the app serving —
 * that is the whole point of the degradation path this check exists to explain.
 */
@Component
public class AiServiceStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(AiServiceStartupCheck.class);

    /** Short: this is a diagnostic, not a readiness gate. A cold AI service is reported, not waited for. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    @org.springframework.beans.factory.annotation.Value("${fixbridge.ai.keep-alive:true}")
    private boolean keepAlive;

    private final FixBridgeProperties props;

    public AiServiceStartupCheck(FixBridgeProperties props) {
        this.props = props;
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        var cfg = props.aiService();
        String url = cfg.baseUrl();
        boolean tokenPresent = cfg.authToken() != null && !cfg.authToken().isBlank();

        // A URL still on the default means the environment variable never arrived — the single most
        // common failure, and previously invisible.
        boolean looksUnset = url == null || url.isBlank() || url.contains("localhost");

        String host;
        try {
            host = url == null ? "<unset>" : URI.create(url).getHost();
        } catch (Exception e) {
            host = "<malformed>";
        }

        log.info("AI service config: host={} tokenPresent={} timeoutSeconds={}",
                host, tokenPresent, cfg.timeoutSeconds());

        if (looksUnset) {
            log.error("AI service URL is unset or points at localhost ({}). Set AI_SERVICE_URL on "
                    + "this service. Repair chat will degrade until it is.", url);
            return;
        }
        if (!tokenPresent) {
            log.error("AI service token is empty. Set AI_SERVICE_TOKEN to match SERVICE_AUTH_TOKEN "
                    + "on the AI service. Repair chat will degrade until it is.");
            return;
        }

        probe(url, cfg.authToken());
    }

    /** One authenticated call. The status code distinguishes the failures that matter. */
    private void probe(String baseUrl, String token) {
        try {
            WebClient client = WebClient.builder().baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + token).build();

            Integer status = client.post().uri("/v1/repair/converse")
                    .bodyValue(java.util.Map.of("messages",
                            java.util.List.of(java.util.Map.of("role", "customer", "text", "startup check"))))
                    .exchangeToMono(r -> reactor.core.publisher.Mono.just(r.statusCode().value()))
                    .block(PROBE_TIMEOUT);

            if (status == null) {
                log.warn("AI service probe returned nothing. Repair chat may degrade.");
            } else if (status == 200) {
                log.info("AI service probe OK — reachable and authenticated.");
            } else if (status == 401 || status == 403) {
                log.error("AI service rejected our credentials ({}). AI_SERVICE_TOKEN does not match "
                        + "SERVICE_AUTH_TOKEN on the AI service.", status);
            } else if (status == 404) {
                log.error("AI service returned 404. Check AI_SERVICE_URL has no trailing slash and "
                        + "no path: expected https://<host>, got {}", baseUrl);
            } else {
                log.warn("AI service probe returned {}. Repair chat may degrade.", status);
            }
        } catch (Exception e) {
            // A cold free-tier instance lands here on first boot and recovers on its own, so this is
            // a warning rather than an error.
            log.warn("AI service unreachable at startup ({}): {}. It may be cold; repair chat will "
                    + "degrade until it answers.", baseUrl, e.getMessage());
        }
    }
    /**
     * Keeps the AI service awake.
     *
     * <p>Hosting tiers that suspend on idle turn the first message of every session into a
     * twenty-second wait, or a failure the customer reads as the assistant being broken. A cheap
     * periodic health call keeps the container resident, so somebody opening the app after lunch
     * gets the same experience as somebody who never closed it.
     *
     * <p>Ten minutes is chosen against the common fifteen-minute idle window — frequent enough to
     * prevent the suspend, infrequent enough to cost nothing. Set fixbridge.ai.keep-alive=false
     * where the platform does not suspend and these calls would be pure waste.
     */
    @Scheduled(fixedDelayString = "${fixbridge.ai.keep-alive-ms:600000}", initialDelay = 600_000)
    public void keepAwake() {
        if (!keepAlive) return;
        String url = props.aiService().baseUrl();
        if (url == null || url.isBlank() || url.contains("localhost")) return;
        try {
            WebClient.builder().baseUrl(url).build()
                    .get().uri("/health").retrieve().toBodilessEntity()
                    .block(Duration.ofSeconds(30));
        } catch (Exception e) {
            // Not worth a warning: the next real request retries, and the startup probe already
            // reported anything structurally wrong.
            log.debug("AI service keep-alive ping failed: {}", e.getMessage());
        }
    }
}
