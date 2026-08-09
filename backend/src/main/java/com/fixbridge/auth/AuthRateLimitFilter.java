package com.fixbridge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Throttles credential endpoints so an attacker cannot simply guess passwords at machine speed.
 *
 * <p>Counts by client IP and, for sign-in, also by the account being targeted — an attacker rotating
 * IPs against one account is the case an IP-only limit misses. State is in memory, which is correct
 * for the single instance this runs on today; a multi-instance deployment should move the counters
 * to Redis so limits are shared.
 */
@Component
@Order(1)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);

    /** Endpoints where guessing is the attack. */
    private static final Set<String> GUARDED = Set.of(
            "/api/auth/login", "/api/auth/register",
            "/api/auth/forgot-password", "/api/auth/reset-password",
            "/api/auth/change-password");

    private static final int MAX_PER_IP = 20;
    private static final int MAX_PER_ACCOUNT = 8;
    private static final Duration WINDOW = Duration.ofMinutes(5);

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile Instant lastSweep = Instant.now();

    private static final class Counter {
        final AtomicInteger hits = new AtomicInteger();
        volatile Instant windowStart = Instant.now();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !GUARDED.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        sweepIfStale();

        String ip = clientIp(request);
        if (exceeded("ip:" + ip + ":" + path, MAX_PER_IP)) {
            log.warn("Rate limit hit for {} from {}", path, ip);
            reject(response);
            return;
        }

        // Buffer the body so the account can be identified here AND still read by the controller.
        HttpServletRequest downstream = new CachedBodyRequestWrapper(request);

        // Sign-in also gets a per-account limit, so one account can't be ground down from many IPs.
        if (path.equals("/api/auth/login")) {
            String account = accountFromBody(downstream);
            if (account != null && exceeded("acct:" + account, MAX_PER_ACCOUNT)) {
                log.warn("Rate limit hit for account {}", account);
                reject(response);
                return;
            }
        }
        chain.doFilter(downstream, response);
    }

    private boolean exceeded(String key, int max) {
        Counter c = counters.computeIfAbsent(key, k -> new Counter());
        synchronized (c) {
            if (Duration.between(c.windowStart, Instant.now()).compareTo(WINDOW) > 0) {
                c.windowStart = Instant.now();
                c.hits.set(0);
            }
            return c.hits.incrementAndGet() > max;
        }
    }

    /**
     * Reads the email from the JSON body without consuming the stream — the request is wrapped so
     * the controller can still read it.
     */
    private String accountFromBody(HttpServletRequest request) {
        if (!(request instanceof CachedBodyRequest cached)) return null;
        try {
            var node = objectMapper.readTree(cached.body());
            String email = node.path("email").asText(null);
            return email == null ? null : email.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Too many attempts. Please wait a few minutes and try again.\"}");
    }

    /** Behind a proxy the socket address is the proxy, so prefer the forwarded client address. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Drop stale counters so the map cannot grow without bound. */
    private void sweepIfStale() {
        if (Duration.between(lastSweep, Instant.now()).toMinutes() < 10) return;
        lastSweep = Instant.now();
        counters.entrySet().removeIf(e ->
                Duration.between(e.getValue().windowStart, Instant.now()).compareTo(WINDOW.multipliedBy(2)) > 0);
    }

    /** Marker for a request whose body has been buffered — see {@link CachedBodyFilter}. */
    public interface CachedBodyRequest {
        String body();
    }
}
