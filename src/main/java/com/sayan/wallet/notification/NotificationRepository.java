package com.sayan.wallet.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    boolean existsByTransactionIdAndUserId(UUID transactionId, UUID userId);

    List<Notification> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);
}