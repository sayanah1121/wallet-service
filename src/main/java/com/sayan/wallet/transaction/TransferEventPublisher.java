package com.sayan.wallet.transaction;

import com.sayan.wallet.config.KafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransferEventPublisher {
    private final KafkaTemplate<String, String> kafka;

    // Publish only after the DB transaction commits, so consumers never see
    // an event for a transfer that was rolled back.
    public void publishAfterCommit(TransferCompletedEvent event) {
        Runnable send = () -> {
            try {
                kafka.send(KafkaConfig.TRANSFER_TOPIC, event.transactionId().toString(), event.toJson())
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to publish event for tx {}", event.transactionId(), ex);
                            }
                        });
            } catch (Exception e) {
                log.error("Failed to publish event for tx {}", event.transactionId(), e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }
}