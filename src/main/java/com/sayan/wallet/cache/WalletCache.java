package com.sayan.wallet.cache;

import com.sayan.wallet.wallet.WalletDtos.WalletResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
@RequiredArgsConstructor
public class WalletCache {
    // short TTL bounds how long a stale value can survive a read/write race
    private static final Duration TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;

    public Optional<WalletResponse> get(UUID userId) {
        try {
            String v = redis.opsForValue().get(key(userId));
            if (v == null) return Optional.empty();
            String[] p = v.split(",");
            return Optional.of(new WalletResponse(UUID.fromString(p[0]), Long.parseLong(p[1]), p[2]));
        } catch (Exception e) {
            log.warn("Wallet cache read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(UUID userId, WalletResponse w) {
        try {
            redis.opsForValue().set(key(userId), w.id() + "," + w.balance() + "," + w.currency(), TTL);
        } catch (Exception e) {
            log.warn("Wallet cache write failed: {}", e.getMessage());
        }
    }

    // Evict only after the DB transaction commits; evicting earlier could let a
    // concurrent reader re-cache the old balance before the new one is visible.
    public void evictAfterCommit(UUID... userIds) {
        Runnable evict = () -> {
            try {
                for (UUID id : userIds) redis.delete(key(id));
            } catch (Exception e) {
                log.warn("Wallet cache evict failed: {}", e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { evict.run(); }
            });
        } else {
            evict.run();
        }
    }

    private static String key(UUID userId) { return "wallet:balance:" + userId; }
}