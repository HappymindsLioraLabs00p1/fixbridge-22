package com.fixbridge.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates a platform-injected {@code DATABASE_URL} into the JDBC form Spring needs.
 *
 * <p>Railway, Heroku and Fly all hand out a single connection string shaped like
 * {@code postgresql://user:password@host:port/database}. That is a libpq URL, not a JDBC one — the
 * driver rejects it outright, so an app deployed to those platforms fails at startup with
 * "Driver claims to not accept jdbcUrl" unless somebody hand-splits it into six variables.
 *
 * <p>Splitting it here means linking a database on the platform is the entire configuration step.
 * Explicit {@code DB_*} variables still win: this only fills in what the platform provided and what
 * nobody set by hand, so an existing deployment pointing at a different database is untouched.
 *
 * <p>Runs as an {@link EnvironmentPostProcessor} rather than a bean because Flyway and the
 * datasource are both built before any bean of ours would exist.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String SOURCE_NAME = "fixbridge-database-url";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        // An explicitly configured URL is a deliberate choice and must not be second-guessed.
        if (hasText(env.getProperty("DB_URL")) || hasText(env.getProperty("DB_HOST"))) {
            return;
        }

        String raw = firstPresent(env, "DATABASE_URL", "POSTGRES_URL", "POSTGRESQL_URL");
        if (!hasText(raw)) {
            return;
        }
        // Already JDBC — nothing to translate.
        if (raw.startsWith("jdbc:")) {
            return;
        }

        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host == null) return;

            int port = uri.getPort() == -1 ? 5432 : uri.getPort();
            String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");

            // The platform's own query string is carried over: it may legitimately carry sslmode.
            // channel_binding is dropped — libpq understands it, the JDBC driver does not, and
            // leaving it in is a connection failure that reads like a credentials problem.
            String query = stripUnsupported(uri.getQuery());

            Map<String, Object> resolved = new HashMap<>();
            resolved.put("spring.datasource.url",
                    "jdbc:postgresql://" + host + ":" + port + "/" + database + query);

            String userInfo = uri.getUserInfo();
            if (hasText(userInfo)) {
                int split = userInfo.indexOf(':');
                resolved.put("spring.datasource.username",
                        split < 0 ? userInfo : userInfo.substring(0, split));
                if (split >= 0) {
                    resolved.put("spring.datasource.password", userInfo.substring(split + 1));
                }
            }

            // Highest precedence so it beats the DB_* defaults in application.yml, which would
            // otherwise resolve to localhost and send the app looking for a database that is not
            // in the container. Never logged — it carries the password.
            env.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, resolved));
        } catch (IllegalArgumentException e) {
            // A malformed value is left alone; the datasource will fail with its own clearer error.
        }
    }

    /** Keeps driver-understood options, drops the libpq-only ones. */
    private static String stripUnsupported(String query) {
        if (!hasText(query)) return "";
        StringBuilder kept = new StringBuilder();
        for (String param : query.split("&")) {
            if (param.isBlank() || param.toLowerCase().startsWith("channel_binding")) continue;
            kept.append(kept.isEmpty() ? "?" : "&").append(param);
        }
        return kept.toString();
    }

    private static String firstPresent(ConfigurableEnvironment env, String... keys) {
        for (String key : keys) {
            String value = env.getProperty(key);
            if (hasText(value)) return value;
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
