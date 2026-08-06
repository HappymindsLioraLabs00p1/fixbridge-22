package com.fixbridge.notification.dto;

import java.time.Instant;

public final class NotificationDtos {

    private NotificationDtos() {}

    /** A user-facing notification feed item. */
    public record View(String template, String channel, String jobId, Instant createdAt) {}
}
