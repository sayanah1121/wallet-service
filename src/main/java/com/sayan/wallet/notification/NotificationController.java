package com.sayan.wallet.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationRepository notifications;

    public record NotificationResponse(UUID id, UUID transactionId, String message, Instant createdAt) {}

    @GetMapping
    public List<NotificationResponse> mine(Authentication auth) {
        return notifications.findTop20ByUserIdOrderByCreatedAtDesc(UUID.fromString(auth.getName()))
                .stream()
                .map(n -> new NotificationResponse(n.getId(), n.getTransactionId(), n.getMessage(), n.getCreatedAt()))
                .toList();
    }
}