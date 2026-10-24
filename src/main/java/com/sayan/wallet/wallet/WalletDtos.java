package com.sayan.wallet.wallet;

import com.sayan.wallet.ledger.Direction;
import com.sayan.wallet.ledger.LedgerEntry;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

public final class WalletDtos {
    private WalletDtos() {}

    // amounts are in paise (minor units): 50000 = INR 500.00
    public record TopUpRequest(@Positive @Max(10_000_000) long amount) {}

    public record WalletResponse(UUID id, long balance, String currency) {}

    public record TopUpResponse(UUID transactionId, long balance, String currency) {}

    public record LedgerEntryResponse(UUID id, UUID transactionId, Direction direction,
                                      long amount, long balanceAfter, Instant createdAt) {
        public static LedgerEntryResponse from(LedgerEntry e) {
            return new LedgerEntryResponse(e.getId(), e.getTransactionId(), e.getDirection(),
                    e.getAmount(), e.getBalanceAfter(), e.getCreatedAt());
        }
    }
}