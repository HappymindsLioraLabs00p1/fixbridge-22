package com.fixbridge.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fixbridge.config.FixBridgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Live storage on Supabase Storage: a private bucket with short-lived signed upload and download
 * URLs, mirroring the GCS implementation so nothing above this layer changes.
 *
 * <p>Signing happens here rather than in the browser because it needs the service-role key, which
 * grants unrestricted access to every bucket. That key must never leave the server; the browser
 * only ever receives a URL that expires.
 *
 * <p>Active only when storage is live <em>and</em> the provider is set to {@code supabase}, so
 * enabling it is a deliberate act and an existing GCS deployment is untouched.
 */
@Service
@ConditionalOnExpression(
        "'${fixbridge.storage.stub-mode:true}' == 'false' "
      + "and '${fixbridge.storage.provider:gcs}' == 'supabase'")
public class SupabaseStorageService implements StorageService {

    /** Signing is a small JSON round-trip; a long timeout would just hold a request thread. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient client;
    private final String bucket;
    private final long ttlSeconds;
    private final String baseUrl;

    public SupabaseStorageService(FixBridgeProperties props) {
        var storage = props.storage();
        var supabase = storage.supabase();
        if (supabase == null || supabase.url() == null || supabase.url().isBlank()) {
            // Failing at construction is deliberate: a storage service that silently cannot sign
            // would surface later as unexplained upload failures for customers.
            throw new IllegalStateException(
                    "STORAGE_PROVIDER is supabase but SUPABASE_URL is not set");
        }
        if (supabase.serviceKey() == null || supabase.serviceKey().isBlank()) {
            throw new IllegalStateException(
                    "STORAGE_PROVIDER is supabase but SUPABASE_SERVICE_KEY is not set");
        }
        this.bucket = storage.bucket();
        this.ttlSeconds = storage.signedUrlTtlMinutes() * 60;
        this.baseUrl = trimTrailingSlash(supabase.url());
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + supabase.serviceKey())
                .defaultHeader("apikey", supabase.serviceKey())
                .build();
    }

    /**
     * A one-time upload target. Supabase returns a relative signed path plus a token; the browser
     * PUTs the file to the absolute form of that path.
     */
    @Override
    public UploadTarget createUploadUrl(String contentType) {
        String key = "uploads/" + UUID.randomUUID();
        JsonNode body = post("/storage/v1/object/upload/sign/" + bucket + "/" + key, Map.of());
        String signed = body.path("url").asText(null);
        if (signed == null || signed.isBlank()) {
            throw new IllegalStateException("Supabase did not return an upload URL");
        }
        return new UploadTarget(key, absolute(signed), "PUT", contentType);
    }

    /** A short-lived signed URL to view a stored object. */
    @Override
    public String createDownloadUrl(String objectKey) {
        JsonNode body = post("/storage/v1/object/sign/" + bucket + "/" + objectKey,
                Map.of("expiresIn", ttlSeconds));
        String signed = body.path("signedURL").asText(null);
        if (signed == null || signed.isBlank()) {
            throw new IllegalStateException("Supabase did not return a download URL");
        }
        return absolute(signed);
    }

    private JsonNode post(String path, Map<String, Object> body) {
        JsonNode result = client.post().uri(path).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block(TIMEOUT);
        if (result == null) {
            throw new IllegalStateException("Supabase storage returned no response for " + path);
        }
        return result;
    }

    /** Supabase returns paths relative to /storage/v1; the browser needs an absolute URL. */
    private String absolute(String signedPath) {
        if (signedPath.startsWith("http")) return signedPath;
        String suffix = signedPath.startsWith("/") ? signedPath : "/" + signedPath;
        // Already-prefixed paths must not gain a second /storage/v1.
        String prefix = suffix.startsWith("/storage/v1") ? "" : "/storage/v1";
        return baseUrl + prefix + suffix;
    }

    /** Visible for testing: URL assembly is the part that breaks without a network call. */
    String absoluteForTest(String signedPath) {
        return absolute(signedPath);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
