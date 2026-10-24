package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.ScriptedWebhookServer.Answer;
import com.sayan.wallet.ScriptedWebhookServer.Attempt;
import com.sayan.wallet.notification.NotificationConsumer;
import com.sayan.wallet.transaction.TransferCompletedEvent;
import com.sayan.wallet.transaction.TransferDtos.TransferRequest;
import com.sayan.wallet.transaction.TransferDtos.TransferResponse;
import com.sayan.wallet.transaction.TransferService;
import com.sayan.wallet.webhook.WebhookDeliveryConsumer;
import com.sayan.wallet.webhook.WebhookEndpoint;
import com.sayan.wallet.webhook.WebhookEndpointRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;

// Part 2, category 6: a worker crashes after acting on an event but before committing the offset,
// so Kafka redelivers the event to the restarted worker (at-least-once).
//
// The crash is simulated with a MANUAL-ack container that processes the record and is then stopped
// without acknowledging: exactly the offset state a killed process leaves behind. The restarted worker
// joins the same group and receives the record again.
class ConsumerReplayTest extends IntegrationTestBase {

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired ConsumerFactory<?, ?> consumerFactory;
    @Autowired DefaultErrorHandler kafkaErrorHandler;
    @Autowired WebhookDeliveryConsumer webhookConsumer;
    @Autowired NotificationConsumer notificationConsumer;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired TransferService transfers;

    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : cleanup.reversed()) {
            c.close();
        }
    }

    private String newTopic() throws Exception {
        String topic = "replay-test-" + UUID.randomUUID();
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
        }
        return topic;
    }

    private boolean hasCommittedOffset(String group) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            return !admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get().isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private KafkaMessageListenerContainer<String, String> container(String topic, String group, Object listener,
                                                                   ContainerProperties.AckMode ackMode) throws Exception {
        ContainerProperties props = new ContainerProperties(topic);
        props.setGroupId(group);
        props.setAckMode(ackMode);
        props.setMessageListener(listener);
        var c = new KafkaMessageListenerContainer<>((ConsumerFactory<String, String>) consumerFactory, props);
        c.setCommonErrorHandler(kafkaErrorHandler);
        c.start();
        await(Duration.ofSeconds(30), "partition assignment",
                () -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty());
        return c;
    }

    // Worker 1 processes the record and "dies" before acking; worker 2 restarts on the same group.
    private void crashAfterProcessingThenRestart(String topic, Consumer<String> handler,
                                                 java.util.function.BooleanSupplier firstRunDone) throws Exception {
        String group = "replay-group-" + UUID.randomUUID();
        var worker1 = container(topic, group,
                (AcknowledgingMessageListener<String, String>) (rec, ack) -> handler.accept(rec.value()),
                ContainerProperties.AckMode.MANUAL);
        await(Duration.ofSeconds(30), "first worker to process the event", firstRunDone);
        worker1.stop(); // crash: the record was handled but its offset was never acknowledged
        assertThat(hasCommittedOffset(group)).as("no offset committed by the crashed worker").isFalse();

        var worker2 = container(topic, group, (MessageListener<String, String>) rec -> handler.accept(rec.value()),
                ContainerProperties.AckMode.BATCH);
        cleanup.add(worker2::stop);
        await(Duration.ofSeconds(30), "restarted worker to commit the redelivered record",
                () -> {
                    try {
                        return hasCommittedOffset(group);
                    } catch (Exception e) {
                        return false;
                    }
                });
    }

    @Test
    @DisplayName("P2 TC-1.1a: webhook worker crashes after firing the webhook, before committing -> merchant must not get it twice")
    void webhookWorkerCrash_merchantReceivesEventOnce() throws Exception {
        String topic = newTopic();
        ScriptedWebhookServer merchant = new ScriptedWebhookServer(n -> Answer.status(200));
        cleanup.add(merchant);
        UUID merchantUser = newUser(0);
        WebhookEndpoint ep = new WebhookEndpoint();
        ep.setUserId(merchantUser);
        ep.setUrl(merchant.url());
        ep.setSecret("whsec_test_" + UUID.randomUUID());
        endpoints.save(ep);
        cleanup.add(() -> {
            ep.setActive(false);
            endpoints.save(ep);
        });

        UUID txId = UUID.randomUUID();
        String payload = new TransferCompletedEvent(txId, UUID.randomUUID(), merchantUser, 1_000, "INR",
                Instant.now().toString()).toJson();
        kafka.send(topic, payload).get();

        crashAfterProcessingThenRestart(topic, webhookConsumer::onTransfer, () -> merchant.attempts().size() >= 1);
        Thread.sleep(1_000);

        List<Attempt> deliveries = merchant.attempts();
        TestMetrics.record("P2-TC-1.1a", "webhook_deliveries_of_one_event", deliveries.size());
        TestMetrics.record("P2-TC-1.1a", "deliveries_byte_identical",
                deliveries.stream().map(Attempt::body).distinct().count() == 1);

        // what the merchant could dedupe on: every copy is identical and carries the same transactionId
        assertThat(deliveries).isNotEmpty().allSatisfy(a -> {
            assertThat(a.body()).isEqualTo(payload);
            assertThat(TransferCompletedEvent.fromJson(a.body()).transactionId()).isEqualTo(txId);
        });
        assertThat(deliveries)
                .as("merchant received transfer %s %d times; WebhookDeliveryConsumer keeps no record of "
                        + "already-delivered events, so a redelivered message is sent again", txId, deliveries.size())
                .hasSize(1);
    }

    @Test
    @DisplayName("P2 TC-1.1b: notification worker crashes before committing -> no duplicate notifications")
    void notificationWorkerCrash_noDuplicateNotifications() throws Exception {
        String topic = newTopic();
        UUID sender = newUser(5_000);
        UUID receiver = newUser(0);
        TransferResponse r = transfers.transfer(sender, "replay-" + UUID.randomUUID(),
                new TransferRequest(emailOf(receiver), 1_000));
        String payload = new TransferCompletedEvent(r.transactionId(), sender, receiver, 1_000, "INR",
                Instant.now().toString()).toJson();
        kafka.send(topic, payload).get();

        java.util.concurrent.atomic.AtomicInteger handled = new java.util.concurrent.atomic.AtomicInteger();
        crashAfterProcessingThenRestart(topic, value -> {
            notificationConsumer.onTransfer(value);
            handled.incrementAndGet();
        }, () -> handled.get() >= 1);

        int rows = jdbc.queryForObject("select count(*) from notifications where transaction_id = ?",
                Integer.class, r.transactionId());
        TestMetrics.record("P2-TC-1.1b", "times_event_processed", handled.get());
        TestMetrics.record("P2-TC-1.1b", "notification_rows", rows);
        assertThat(handled.get()).as("the event really was redelivered").isEqualTo(2);
        assertThat(rows).isEqualTo(2);
    }
}
