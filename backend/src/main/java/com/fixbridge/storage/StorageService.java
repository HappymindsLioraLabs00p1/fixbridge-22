package com.fixbridge.storage;

/**
 * Private object storage for photos/video/documents. Uploads go browser → storage directly via a
 * signed PUT URL; downloads use short-lived signed GET URLs. Stub (local) and live (GCS) implementations.
 */
public interface StorageService {

    /** A one-time signed target the browser PUTs a file to. */
    record UploadTarget(String objectKey, String uploadUrl, String method, String contentType) {}

    UploadTarget createUploadUrl(String contentType);

    /** A short-lived signed URL to view a stored object. */
    String createDownloadUrl(String objectKey);
}
