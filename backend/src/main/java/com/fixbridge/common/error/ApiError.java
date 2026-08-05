package com.fixbridge.common.error;

import java.time.Instant;
import java.util.List;

/** Uniform, safe error body returned to clients. Never contains stack traces or raw provider errors. */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        List<String> details
) {
    public static ApiError of(int status, String error, String message, List<String> details) {
        return new ApiError(Instant.now(), status, error, message, details);
    }
}
