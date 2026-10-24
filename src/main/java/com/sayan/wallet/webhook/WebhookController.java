package com.sayan.wallet.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository endpoints;

    public record CreateWebhookRequest(
            @NotBlank @Size(max = 500) @Pattern(regexp = "^https?://.+") String url) {}

    // the secret is returned only once, at creation
    public record CreatedWebhook(UUID id, String url, String secret) {}

    public record WebhookInfo(UUID id, String url, boolean active, Instant createdAt) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedWebhook create(Authentication auth, @Valid @RequestBody CreateWebhookRequest req) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);

        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setUserId(UUID.fromString(auth.getName()));
        ep.setUrl(req.url().trim());
        ep.setSecret("whsec_" + HexFormat.of().formatHex(bytes));
        endpoints.save(ep);
        return new CreatedWebhook(ep.getId(), ep.getUrl(), ep.getSecret());
    }

    @GetMapping
    public List<WebhookInfo> list(Authentication auth) {
        return endpoints.findByUserId(UUID.fromString(auth.getName())).stream()
                .map(e -> new WebhookInfo(e.getId(), e.getUrl(), e.isActive(), e.getCreatedAt()))
                .toList();
    }
}