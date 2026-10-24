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
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryConsumer {
    private final WebhookEndpointRepository endpoints;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // Any exception thrown here triggers the error handler: 3 retries, then the DLT.
    // A retry re-sends to every endpoint, so receivers must dedupe on transactionId.
    @KafkaListener(topics = KafkaConfig.TRANSFER_TOPIC, groupId = "wallet-webhooks")
    public void onTransfer(String payload) {
        TransferCompletedEvent event = TransferCompletedEvent.fromJson(payload);
        for (WebhookEndpoint ep : endpoints.findByUserIdAndActiveTrue(event.toUserId())) {
            deliver(ep, payload);
        }
    }

    private void deliver(WebhookEndpoint ep, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ep.getUrl()))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Wallet-Event", "transfer.completed")
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