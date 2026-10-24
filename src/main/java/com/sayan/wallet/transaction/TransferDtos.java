package com.sayan.wallet.transaction;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public final class TransferDtos {
    private TransferDtos() {}

    // amount is in paise: 10000 = INR 100.00
    public record TransferRequest(
            @NotBlank @Email String toEmail,
            @Positive @Max(10_000_000) long amount) {}

    public record TransferResponse(UUID transactionId, long balance, String currency) {}
}