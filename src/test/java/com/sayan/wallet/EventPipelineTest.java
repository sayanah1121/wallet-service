package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sayan.wallet.config.KafkaConfig;
import com.sayan.wallet.notification.NotificationConsumer;
import com.sayan.wallet.transaction.TransferCompletedEvent;
import com.sayan.wallet.transaction.TransferDtos.TransferRequest;
import com.sayan.wallet.transaction.TransferDtos.TransferResponse;
import com.sayan.wallet.transaction.TransferEventPublisher;
import com.sayan.wallet.transaction.TransferService;
import com.sayan.wallet.webhook.WebhookEndpoint;
import com.sayan.wallet.webhook.WebhookDeliveryConsumer;
import com.sayan.wallet.webhook.WebhookEndpointRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.support.TransactionTemplate;

// Category 3.1: the payment path must not depend on the consumers, and events produced while the
// workers are down must be processed once they come back. Uses the real publisher and real Kafka.
// The "test" profile keeps the @KafkaListener containers stopped, which is our "workers down" state.
class EventPipelineTest extends IntegrationTestSupport {

    @Autowired TransferService transfers;
    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired ConsumerFactory<?, ?> consumerFactory;
    @Autowired NotificationConsumer notificationConsumer;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired TransactionTemplate tx;

    @Autowired WebhookDeliveryConsumer webhookConsumer;
    @Autowired DefaultErrorHandler kafkaErrorHandler;

    private WebhookEndpoint endpoint;
    private final List<MessageListenerContainer> workers = new ArrayList<>();

    @AfterEach
    void stopWorkers() {
        workers.forEach(MessageListenerContainer::stop);
        if (endpoint != null) {
            // stale events from this run must never be delivered to a closed port later
            endpoint.setActive(false);
            endpoints.save(endpoint);
        }
    }

    private int notificationsFor(Set<UUID> txIds) {
        return txIds.stream().mapToInt(id -> jdbc.queryForObject(
                "select count(*) from notifications where transaction_id = ?", Integer.class, id)).sum();
    }

    // A consumer positioned at the current end of the topic, optionally committing that position for its group.
    private KafkaConsumer<String, String> consumerAtEndOfTopic(String groupId, boolean commit) {
        var props = new HashMap<>(consumerFactory.getConfigurationProperties());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        List<TopicPartition> partitions = consumer.partitionsFor(KafkaConfig.TRANSFER_TOPIC).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position); // resolve the end offsets now, before we produce
        if (commit) {
            consumer.commitSync();
        }
        return consumer;
    }

    // The real listener beans in a container on their own group, with the application's error handler.
    @SuppressWarnings("unchecked")
    private void startWorker(String groupId, MessageListener<String, String> listener) {
        ContainerProperties props = new ContainerProperties(KafkaConfig.TRANSFER_TOPIC);
        props.setGroupId(groupId);
        props.setMessageListener(listener);
        var container = new ConcurrentMessageListenerContainer<>((ConsumerFactory<String, String>) consumerFactory, props);
        container.setCommonErrorHandler(kafkaErrorHandler);
        container.start();
        workers.add(container);
    }

    @Test
    @DisplayName("TC-3.1a: workers down -> transfers still succeed, events wait in Kafka and are processed on restart")
    void workersDown_transfersSucceed_eventsProcessedAfterRestart() throws Exception {
        assertThat(registry.getListenerContainers()).isNotEmpty()
                .allSatisfy(c -> assertThat(c.isRunning()).as("consumer %s", c.getListenerId()).isFalse());

        // Private worker groups whose committed offset is "now": exactly the state of a worker that
        // crashed just before the transfers. Private groups keep a locally running copy of the app
        // (which joins the real groups) from stealing partitions.
        String groupPrefix = "restart-test-" + UUID.randomUUID();
        consumerAtEndOfTopic(groupPrefix + "-notifications", true).close();
        consumerAtEndOfTopic(groupPrefix + "-webhooks", true).close();

        UUID sender = newUser(10_000);
        UUID merchant = newUser(0);
        try (ScriptedWebhookServer merchantServer = new ScriptedWebhookServer(n -> ScriptedWebhookServer.Answer.status(200));
             KafkaConsumer<String, String> probe = consumerAtEndOfTopic("probe-" + UUID.randomUUID(), false)) {
            endpoint = new WebhookEndpoint();
            endpoint.setUserId(merchant);
            endpoint.setUrl(merchantServer.url());
            endpoint.setSecret("whsec_test_" + UUID.randomUUID());
            endpoints.save(endpoint);

            // 1. payment path works while every consumer is down
            Set<UUID> txIds = new HashSet<>();
            for (int i = 0; i < 5; i++) {
                TransferResponse r = transfers.transfer(sender, "down-" + UUID.randomUUID(),
                        new TransferRequest(emailOf(merchant), 1_000));
                txIds.add(r.transactionId());
            }
            assertThat(balanceOf(sender)).isEqualTo(5_000);
            assertThat(balanceOf(merchant)).isEqualTo(5_000);
            assertLedgerBalanced();

            // 2. nothing was processed, but every event is durably in the topic
            Thread.sleep(1_000);
            assertThat(notificationsFor(txIds)).isZero();
            assertThat(merchantServer.attempts()).isEmpty();
            Set<UUID> inKafka = new HashSet<>();
            await(Duration.ofSeconds(20), "5 events in Kafka", () -> {
                probe.poll(Duration.ofMillis(500)).forEach(rec ->
                        inKafka.add(TransferCompletedEvent.fromJson(rec.value()).transactionId()));
                return inKafka.containsAll(txIds);
            });

            // 3. restart the workers: they resume from their committed offsets and drain the backlog
            long restart = System.nanoTime();
            startWorker(groupPrefix + "-notifications", rec -> notificationConsumer.onTransfer(rec.value()));
            startWorker(groupPrefix + "-webhooks", rec -> webhookConsumer.onTransfer(rec.value()));
            await(Duration.ofSeconds(90), "10 notifications (sender + receiver per transfer)",
                    () -> notificationsFor(txIds) == 10);
            await(Duration.ofSeconds(90), "5 webhook deliveries", () -> merchantServer.attempts().stream()
                    .map(a -> TransferCompletedEvent.fromJson(a.body()).transactionId())
                    .filter(txIds::contains).distinct().count() == 5);
            long drainMs = (System.nanoTime() - restart) / 1_000_000;

            TestMetrics.record("TC-3.1a", "transfers_while_down", 5);
            TestMetrics.record("TC-3.1a", "events_found_in_kafka", txIds.size());
            TestMetrics.record("TC-3.1a", "notifications_after_restart", notificationsFor(txIds));
            TestMetrics.record("TC-3.1a", "webhooks_after_restart", 5);
            TestMetrics.record("TC-3.1a", "backlog_drain_ms", drainMs);
        }
    }

    @Test
    @DisplayName("TC-3.1b: a redelivered event does not create duplicate notifications")
    void redeliveredEvent_isIdempotent() {
        UUID sender = newUser(5_000);
        UUID receiver = newUser(0);
        TransferResponse r = transfers.transfer(sender, "redeliver-" + UUID.randomUUID(),
                new TransferRequest(emailOf(receiver), 1_000));
        String payload = new TransferCompletedEvent(r.transactionId(), sender, receiver, 1_000, "INR",
                Instant.now().toString()).toJson();

        // Kafka is at-least-once: simulate the same record arriving three times
        notificationConsumer.onTransfer(payload);
        notificationConsumer.onTransfer(payload);
        notificationConsumer.onTransfer(payload);

        assertThat(notificationsFor(Set.of(r.transactionId()))).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-3.1c: events are published only after commit; rolled-back transfers publish nothing")
    @SuppressWarnings("unchecked")
    void publisher_sendsOnlyAfterCommit() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        TransferEventPublisher publisher = new TransferEventPublisher(kafka);
        TransferCompletedEvent event = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 100, "INR", Instant.now().toString());

        tx.executeWithoutResult(status -> {
            publisher.publishAfterCommit(event);
            verify(kafka, never()).send(anyString(), anyString(), anyString());
            status.setRollbackOnly();
        });
        verify(kafka, never()).send(anyString(), anyString(), anyString());

        tx.executeWithoutResult(status -> publisher.publishAfterCommit(event));
        verify(kafka, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("TC-3.1d: a broker error while publishing never fails the already-committed transfer")
    @SuppressWarnings("unchecked")
    void publisherFailure_isSwallowed() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("broker down"));
        TransferEventPublisher publisher = new TransferEventPublisher(kafka);
        TransferCompletedEvent event = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 100, "INR", Instant.now().toString());

        assertThatCode(() -> tx.executeWithoutResult(status -> publisher.publishAfterCommit(event)))
                .doesNotThrowAnyException();
    }
}
