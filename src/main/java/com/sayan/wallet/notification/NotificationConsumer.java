package com.sayan.wallet.notification;

import com.sayan.wallet.config.KafkaConfig;
import com.sayan.wallet.transaction.TransferCompletedEvent;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {
    private final NotificationRepository notifications;

    @KafkaListener(topics = KafkaConfig.TRANSFER_TOPIC, groupId = "wallet-notifications")
    public void onTransfer(String payload) {
        TransferCompletedEvent e = TransferCompletedEvent.fromJson(payload);
        String money = String.format("%s %d.%02d", e.currency(), e.amount() / 100, e.amount() % 100);

        save(e.fromUserId(), e.transactionId(), "You sent " + money);
        save(e.toUserId(), e.transactionId(), "You received " + money);
    }

    // Kafka delivers at least once, so a redelivered event must not create a duplicate.
    // The existence check handles the common case; the unique constraint is the backstop.
    private void save(UUID userId, UUID transactionId, String message) {
        if (notifications.existsByTransactionIdAndUserId(transactionId, userId)) {
            return;
        }
        Notification n = new Notification();
        n.setUserId(userId);
        n.setTransactionId(transactionId);
        n.setMessage(message);
        try {
            notifications.saveAndFlush(n);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Duplicate notification ignored for tx {}", transactionId);
        }
    }
}