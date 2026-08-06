package com.fixbridge.notification;

import com.fixbridge.auth.SecurityUtil;
import com.fixbridge.notification.dto.NotificationDtos;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    /** The signed-in user's own notification feed. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<NotificationDtos.View> mine() {
        return notifications.listFor(SecurityUtil.currentUser().id());
    }
}
