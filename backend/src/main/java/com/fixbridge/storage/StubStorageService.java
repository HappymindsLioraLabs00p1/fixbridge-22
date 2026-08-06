package com.fixbridge.storage;

import com.fixbridge.config.FixBridgeProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Frontend-first storage: issues upload/download URLs that point at this backend's own local object
 * store ({@link LocalStorageController}), so photo upload genuinely works with no GCP. Replaced by
 * {@link GcsStorageService} when {@code fixbridge.ai.stub-mode=false}.
 */
@Service
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "true", matchIfMissing = true)
public class StubStorageService implements StorageService {

    private final String baseUrl;

    public StubStorageService(FixBridgeProperties props) {
        this.baseUrl = props.storage().publicBaseUrl();
    }

    @Override
    public UploadTarget createUploadUrl(String contentType) {
        String key = "uploads/" + UUID.randomUUID() + extensionFor(contentType);
        return new UploadTarget(key, baseUrl + "/api/local-storage/" + key, "PUT", contentType);
    }

    @Override
    public String createDownloadUrl(String objectKey) {
        return baseUrl + "/api/local-storage/" + objectKey;
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) return "";
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            default -> "";
        };
    }
}
