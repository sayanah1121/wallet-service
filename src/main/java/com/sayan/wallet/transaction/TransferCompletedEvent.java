package com.sayan.wallet.transaction;

import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public record TransferCompletedEvent(
        UUID transactionId,
        UUID fromUserId,
        UUID toUserId,
        long amount,
        String currency,
        String occurredAt) {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public String toJson() { return MAPPER.writeValueAsString(this); }

    public static TransferCompletedEvent fromJson(String json) {
        return MAPPER.readValue(json, TransferCompletedEvent.class);
    }
}