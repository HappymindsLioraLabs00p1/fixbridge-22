package com.fixbridge.common;

import java.util.UUID;

/**
 * A request id shared between the Java backend and the Python AI service, so a single job can be
 * followed across both in a log search. Java mints it; Python echoes it back and stamps it on every
 * line it logs.
 */
public final class CorrelationId {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationId() {}

    public static String current() {
        String existing = CURRENT.get();
        if (existing == null) {
            existing = "FIX-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            CURRENT.set(existing);
        }
        return existing;
    }

    public static void set(String value) {
        CURRENT.set(value);
    }

    /** Must be called when the request ends — a pooled thread would otherwise carry the id onward. */
    public static void clear() {
        CURRENT.remove();
    }
}
