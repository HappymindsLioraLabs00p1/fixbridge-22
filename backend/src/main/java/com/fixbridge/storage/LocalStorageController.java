package com.fixbridge.storage;

import com.fixbridge.common.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Local object store used ONLY in stub mode — makes signed-URL upload/download genuinely work with no
 * GCP. Objects are written under the temp dir. Not registered when {@code stub-mode=false} (real uploads
 * go straight to GCS). Path traversal is rejected.
 */
@RestController
@ConditionalOnProperty(prefix = "fixbridge.storage", name = "stub-mode", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/local-storage")
public class LocalStorageController {

    private static final String PREFIX = "/api/local-storage/";
    private final Path root = Path.of(System.getProperty("java.io.tmpdir"), "fixbridge-storage");

    @PutMapping("/**")
    public ResponseEntity<Void> upload(HttpServletRequest request, @RequestBody(required = false) byte[] body)
            throws IOException {
        Path dest = resolve(keyOf(request));
        Files.createDirectories(dest.getParent());
        Files.write(dest, body == null ? new byte[0] : body);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/**")
    public ResponseEntity<byte[]> download(HttpServletRequest request) throws IOException {
        Path src = resolve(keyOf(request));
        if (!Files.exists(src)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(contentType(src.toString())).body(Files.readAllBytes(src));
    }

    private String keyOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.length() > PREFIX.length() ? uri.substring(PREFIX.length()) : "";
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid object key");
        }
        return p;
    }

    private MediaType contentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".mp4")) return MediaType.parseMediaType("video/mp4");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
