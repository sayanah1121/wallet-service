package com.sayan.wallet.idempotency;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "idempotency_keys")
@Getter @Setter @NoArgsConstructor
public class IdempotencyKey {
    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idem_key", nullable = false)
    private String idemKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "balance_after")
    private Long balanceAfter;
}