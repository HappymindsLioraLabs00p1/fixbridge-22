package com.fixbridge.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues one-time signed upload targets. The browser PUTs the file directly to the returned URL, then
 * sends the {@code objectKey} back with the report (never the raw bytes through this API).
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

    private final StorageService storage;

    public MediaController(StorageService storage) {
        this.storage = storage;
    }

    public record UploadUrlRequest(@NotBlank String contentType) {}

    @PostMapping("/upload-url")
    @PreAuthorize("isAuthenticated()")
    public StorageService.UploadTarget uploadUrl(@RequestBody UploadUrlRequest req) {
        return storage.createUploadUrl(req.contentType());
    }
}
