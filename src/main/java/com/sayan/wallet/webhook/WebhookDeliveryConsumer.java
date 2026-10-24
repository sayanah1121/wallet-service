package com.sayan.wallet.webhook;

import com.sayan.wallet.config.KafkaConfig;
import com.sayan.wallet.transaction.TransferCompletedEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryConsumer {
    private final WebhookEndpointRepository endpoints;
    private final JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // Any exception thrown here triggers the error handler: 3 retries, then the DLT.
    // Kafka delivers at least once, so successful deliveries are recorded per (event, endpoint):
    // a redelivered record or a retry skips endpoints that already got it. A crash between the
    // POST and the insert can still repeat one delivery, so receivers should dedupe on X-Wallet-Event-Id.
    @KafkaListener(topics = KafkaConfig.TRANSFER_TOPIC, groupId = "wallet-webhooks")
    public void onTransfer(String payload) {
        TransferCompletedEvent event = TransferCompletedEvent.fromJson(payload);
        for (WebhookEndpoint ep : endpoints.findByUserIdAndActiveTrue(event.toUserId())) {
            if (alreadyDelivered(event.transactionId(), ep.getId())) {
                log.debug("Webhook for tx {} already delivered to {}", event.transactionId(), ep.getUrl());
                continue;
            }
            deliver(ep, event.transactionId(), payload);
            jdbc.update("""
                    INSERT INTO webhook_deliveries (transaction_id, endpoint_id) VALUES (?, ?)
                    ON CONFLICT DO NOTHING
                    """, event.transactionId(), ep.getId());
        }
    }

    private boolean alreadyDelivered(UUID transactionId, UUID endpointId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM webhook_deliveries WHERE transaction_id = ? AND endpoint_id = ?)",
                Boolean.class, transactionId, endpointId));
    }

    private void deliver(WebhookEndpoint ep, UUID eventId, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ep.getUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Wallet-Event", "transfer.completed")
                .header("X-Wallet-Event-Id", eventId.toString())
                .header("X-Wallet-Signature", "sha256=" + sign(ep.getSecret(), body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Webhook " + ep.getUrl() + " returned " + response.statusCode());
            }
            log.info("Webhook delivered to {}", ep.getUrl());
        } catch (IOException e) {
            throw new IllegalStateException("Webhook " + ep.getUrl() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook delivery interrupted", e);
        }
    }

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}