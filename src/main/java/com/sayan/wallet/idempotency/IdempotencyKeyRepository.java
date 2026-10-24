package com.sayan.wallet.idempotency;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

    // Returns 1 if we claimed the key, 0 if it already exists.
    // If another transaction is inserting the same key, this call waits until it commits or rolls back.
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (user_id, idem_key, request_hash)
            VALUES (:userId, :idemKey, :hash)
            ON CONFLICT (user_id, idem_key) DO NOTHING
            """, nativeQuery = true)
    int tryInsert(@Param("userId") UUID userId,
                  @Param("idemKey") String idemKey,
                  @Param("hash") String hash);

    Optional<IdempotencyKey> findByUserIdAndIdemKey(UUID userId, String idemKey);
}