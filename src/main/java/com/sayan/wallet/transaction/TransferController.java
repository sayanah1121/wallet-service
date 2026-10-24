package com.sayan.wallet.transaction;

import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.transaction.TransferDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transfers")
@RequiredArgsConstructor
public class TransferController {
    private final TransferService transferService;

    // Retried outside TransferService's transaction: a deadlock (40P01), serialization failure (40001)
    // or lock timeout rolls back everything, including the idempotency key claim, so a fresh attempt is safe.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Retryable(includes = ConcurrencyFailureException.class, maxRetries = 3, delay = 50, multiplier = 2, jitter = 20)
    public TransferResponse transfer(Authentication auth,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest req) {
        String key = idempotencyKey.trim();
        if (key.isEmpty() || key.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1 to 100 characters");
        }
        return transferService.transfer(UUID.fromString(auth.getName()), key, req);
    }
}