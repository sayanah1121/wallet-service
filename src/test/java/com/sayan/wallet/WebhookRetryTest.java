package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.ScriptedWebhookServer.Answer;
import com.sayan.wallet.ScriptedWebhookServer.Attempt;
import com.sayan.wallet.transaction.TransferCompletedEvent;
import com.sayan.wallet.webhook.WebhookDeliveryConsumer;
import com.sayan.wallet.webhook.WebhookEndpoint;
import com.sayan.wallet.webhook.WebhookEndpointRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

// Category 3.2: an unstable merchant endpoint, delivered through real Kafka with the app's own
// error handler (exponential backoff 1s/2s/4s, then the dead-letter topic). Each test uses a
// private topic and consumer group so nothing else on the broker can interfere.
class WebhookRetryTest extends IntegrationTestBase {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired ConsumerFactory<?, ?> consumerFactory;
    @Autowired DefaultErrorHandler kafkaErrorHandler;
    @Autowired WebhookDeliveryConsumer webhookConsumer;
    @Autowired WebhookEndpointRepository endpoints;

    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : cleanup.reversed()) {
            c.close();
        }
    }

    private record Fixture(String topic, String payload, WebhookEndpoint endpoint, ScriptedWebhookServer server) {}

    @SuppressWarnings("unchecked")
    private Fixture start(IntFunction<Answer> script) throws Exception {
        String topic = "webhook-retry-test-" + UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1),
                    new NewTopic(topic + ".DLT", 1, (short) 1))).all().get();
        }

        ScriptedWebhookServer server = new ScriptedWebhookServer(script);
        cleanup.add(server);

        UUID merchant = newUser(0);
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setUserId(merchant);
        ep.setUrl(server.url());
        ep.setSecret("whsec_test_" + UUID.randomUUID());
        endpoints.save(ep);
        cleanup.add(() -> {
            ep.setActive(false);
            endpoints.save(ep);
        });

        ContainerProperties props = new ContainerProperties(topic);
        props.setGroupId("webhook-retry-test-" + UUID.randomUUID());
        props.setMessageListener((MessageListener<String, String>) rec -> webhookConsumer.onTransfer(rec.value()));
        var container = new KafkaMessageListenerContainer<>((ConsumerFactory<String, String>) consumerFactory, props);
        container.setCommonErrorHandler(kafkaErrorHandler);
        container.start();
        cleanup.add(container::stop);
        await(Duration.ofSeconds(30), "partition assignment",
                () -> container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty());

        String payload = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(), merchant,
                1_000, "INR", Instant.now().toString()).toJson();
        kafka.send(topic, payload).get();
        return new Fixture(topic, payload, ep, server);
    }

    private static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private List<ConsumerRecord<String, String>> readAll(String topic, Duration window) {
        Map<String, Object> config = consumerFactory.getConfigurationProperties();
        var props = new java.util.HashMap<>(config);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + window.toNanos();
            while (System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(out::add);
            }
        }
        return out;
    }

    private static long gapMs(List<Attempt> attempts, int i) {
        return (attempts.get(i).receivedAtNanos() - attempts.get(i - 1).receivedAtNanos()) / 1_000_000;
    }

    @Test
    @DisplayName("TC-3.2a: endpoint fails 3 times (HTTP 500) then succeeds on attempt 4 with exponential backoff")
    void failsThreeTimesThenSucceeds_withExponentialBackoff() throws Exception {
        Fixture f = start(n -> n <= 3 ? Answer.status(500) : Answer.status(200));

        await(Duration.ofSeconds(30), "4 delivery attempts", () -> f.server().attempts().size() >= 4);
        Thread.sleep(3_000); // give a wrongly configured handler the chance to send a 5th attempt
        List<Attempt> attempts = f.server().attempts();

        assertThat(attempts).hasSize(4);
        assertThat(attempts).extracting(Attempt::answeredWith).containsExactly(500, 500, 500, 200);
        // backoff 1s, 2s, 4s (allowing for scheduling jitter)
        assertThat(gapMs(attempts, 1)).isBetween(900L, 3_000L);
        assertThat(gapMs(attempts, 2)).isBetween(1_800L, 5_000L);
        assertThat(gapMs(attempts, 3)).isBetween(3_600L, 9_000L);
        // every attempt is the same signed payload, so the merchant can dedupe on transactionId
        String expectedSignature = hmac(f.endpoint().getSecret(), f.payload());
        assertThat(attempts).allSatisfy(a -> {
            assertThat(a.body()).isEqualTo(f.payload());
            assertThat(a.signature()).isEqualTo(expectedSignature);
        });
        assertThat(readAll(f.topic() + ".DLT", Duration.ofSeconds(3))).isEmpty();

        TestMetrics.record("TC-3.2a", "attempts", attempts.size());
        TestMetrics.record("TC-3.2a", "gap1_ms", gapMs(attempts, 1));
        TestMetrics.record("TC-3.2a", "gap2_ms", gapMs(attempts, 2));
        TestMetrics.record("TC-3.2a", "gap3_ms", gapMs(attempts, 3));
        TestMetrics.record("TC-3.2a", "successful_deliveries", 1);
        TestMetrics.record("TC-3.2a", "dlt_records", 0);
    }

    @Test
    @DisplayName("TC-3.2b: endpoint times out on the first attempt, delivery succeeds on the retry")
    void timeoutThenSuccess() throws Exception {
        // the consumer's request timeout is 5s
        Fixture f = start(n -> n == 1 ? Answer.hang(Duration.ofSeconds(7)) : Answer.status(200));

        await(Duration.ofSeconds(30), "2 delivery attempts", () -> f.server().attempts().size() >= 2);
        Thread.sleep(3_000);
        List<Attempt> attempts = f.server().attempts();

        assertThat(attempts).hasSize(2);
        assertThat(gapMs(attempts, 1)).isGreaterThanOrEqualTo(5_000L);
        assertThat(readAll(f.topic() + ".DLT", Duration.ofSeconds(3))).isEmpty();
        TestMetrics.record("TC-3.2b", "attempts", attempts.size());
        TestMetrics.record("TC-3.2b", "gap1_ms", gapMs(attempts, 1));
    }

    @Test
    @DisplayName("TC-3.2c: endpoint that never recovers gets 4 attempts, then the event is parked in the DLT")
    void permanentFailure_goesToDeadLetterTopic() throws Exception {
        Fixture f = start(n -> Answer.status(500));

        await(Duration.ofSeconds(30), "4 delivery attempts", () -> f.server().attempts().size() >= 4);
        // KafkaConfig provisions "<topic>.DLT" for dead letters; also look at the recoverer's own default
        // name so a mismatch is reported precisely instead of as a timeout
        List<ConsumerRecord<String, String>> dlt = new ArrayList<>();
        List<ConsumerRecord<String, String>> elsewhere = new ArrayList<>();
        await(Duration.ofSeconds(20), "record in a dead-letter topic", () -> {
            dlt.addAll(readAll(f.topic() + ".DLT", Duration.ofSeconds(2)));
            elsewhere.addAll(readAll(f.topic() + "-dlt", Duration.ofSeconds(2)));
            return !dlt.isEmpty() || !elsewhere.isEmpty();
        });
        TestMetrics.record("TC-3.2c", "attempts", f.server().attempts().size());
        TestMetrics.record("TC-3.2c", "records_in_provisioned_.DLT", dlt.size());
        TestMetrics.record("TC-3.2c", "records_in_unprovisioned_-dlt", elsewhere.size());

        assertThat(f.server().attempts()).hasSize(4);
        assertThat(elsewhere)
                .as("dead letter was published to %s-dlt, a topic KafkaConfig never provisions, instead of %s.DLT",
                        f.topic(), f.topic())
                .isEmpty();
        assertThat(dlt).hasSize(1);
        assertThat(dlt.get(0).value()).isEqualTo(f.payload());
        assertThat(new String(dlt.get(0).headers().lastHeader("kafka_dlt-exception-message").value(),
                StandardCharsets.UTF_8)).contains("returned 500");
    }
}
