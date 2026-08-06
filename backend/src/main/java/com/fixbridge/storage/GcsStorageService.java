package com.fixbridge.storage;

import com.fixbridge.config.FixBridgeProperties;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Live storage on Google Cloud Storage: private bucket + V4 signed PUT/GET URLs. Active only when
 * {@code fixbridge.ai.stub-mode=false}. Uses application default credentials (Workload Identity in GKE).
 */
@Service
@ConditionalOnProperty(prefix = "fixbridge.ai", name = "stub-mode", havingValue = "false")
public class GcsStorageService implements StorageService {

    private final Storage storage;
    private final String bucket;
    private final long ttlMinutes;

    public GcsStorageService(FixBridgeProperties props) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = props.storage().bucket();
        this.ttlMinutes = props.storage().signedUrlTtlMinutes();
    }

    @Override
    public UploadTarget createUploadUrl(String contentType) {
        String key = "uploads/" + UUID.randomUUID();
        BlobInfo blob = BlobInfo.newBuilder(bucket, key).setContentType(contentType).build();
        String url = storage.signUrl(blob, ttlMinutes, TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.PUT),
                Storage.SignUrlOption.withContentType(),
                Storage.SignUrlOption.withV4Signature()).toString();
        return new UploadTarget(key, url, "PUT", contentType);
    }

    @Override
    public String createDownloadUrl(String objectKey) {
        BlobInfo blob = BlobInfo.newBuilder(bucket, objectKey).build();
        return storage.signUrl(blob, ttlMinutes, TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature()).toString();
    }
}
