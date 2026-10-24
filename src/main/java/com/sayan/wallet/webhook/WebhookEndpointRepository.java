package com.sayan.wallet.webhook;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {
    List<WebhookEndpoint> findByUserId(UUID userId);

    List<WebhookEndpoint> findByUserIdAndActiveTrue(UUID userId);
}