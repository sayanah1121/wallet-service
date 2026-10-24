package com.sayan.wallet.transaction;

import com.sayan.wallet.cache.WalletCache;
import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.idempotency.IdempotencyKey;
import com.sayan.wallet.idempotency.IdempotencyKeyRepository;
import com.sayan.wallet.ledger.Direction;
import com.sayan.wallet.ledger.LedgerEntryRepository;
import com.sayan.wallet.transaction.TransferDtos.*;
import com.sayan.wallet.user.User;
import com.sayan.wallet.user.UserRepository;
import com.sayan.wallet.wallet.Wallet;
import com.sayan.wallet.wallet.WalletRepository;
import com.sayan.wallet.wallet.WalletService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {
    private final UserRepository users;
    private final WalletRepository wallets;
    private final WalletService walletService;
    private final PaymentTransactionRepository transactions;
    private final LedgerEntryRepository ledger;
    private final IdempotencyKeyRepository idempotency;
    private final WalletCache cache;
    private final TransferEventPublisher events;

    @Transactional
    public TransferResponse transfer(UUID senderUserId, String idemKey, TransferRequest req) {
        String email = req.toEmail().trim().toLowerCase();
        String requestHash = sha256(email + "|" + req.amount());

        // 1. Claim the idempotency key. 0 rows means a request with this key already exists.
        if (idempotency.tryInsert(senderUserId, idemKey, requestHash) == 0) {
            return replay(senderUserId, idemKey, requestHash);
        }

        // 2. Resolve both wallets
        UUID senderWalletId = wallets.findIdByUserId(senderUserId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found"));
        User recipient = users.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Recipient not found"));
        UUID recipientWalletId = wallets.findIdByUserId(recipient.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Recipient wallet not found"));

        if (senderWalletId.equals(recipientWalletId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot transfer to yourself");
        }

        // 3. Lock both rows in a fixed order, then check the balance on the locked rows
        Map<UUID, Wallet> locked = walletService.lockInOrder(senderWalletId, recipientWalletId);
        Wallet sender = locked.get(senderWalletId);
        Wallet receiver = locked.get(recipientWalletId);

        if (sender.getBalance() < req.amount()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds");
        }

        // 4. Record the transaction and move the money
        PaymentTransaction tx = new PaymentTransaction();
        tx.setType(TransactionType.TRANSFER);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(req.amount());
        tx.setCurrency(sender.getCurrency());
        tx.setDescription("Transfer to " + email);
        transactions.save(tx);

        sender.setBalance(Math.subtractExact(sender.getBalance(), req.amount()));
        receiver.setBalance(Math.addExact(receiver.getBalance(), req.amount()));

        ledger.save(walletService.entry(tx.getId(), sender.getId(), Direction.DEBIT,
                req.amount(), sender.getBalance()));
        ledger.save(walletService.entry(tx.getId(), receiver.getId(), Direction.CREDIT,
                req.amount(), receiver.getBalance()));

        // 5. Save the result against the key so retries return the same response
        IdempotencyKey record = idempotency.findByUserIdAndIdemKey(senderUserId, idemKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency record missing"));
        record.setTransactionId(tx.getId());
        record.setBalanceAfter(sender.getBalance());

        // 6. After commit: drop cached balances and publish the event
        cache.evictAfterCommit(senderUserId, recipient.getId());
        events.publishAfterCommit(new TransferCompletedEvent(
                tx.getId(), senderUserId, recipient.getId(),
                req.amount(), sender.getCurrency(), Instant.now().toString()));

        return new TransferResponse(tx.getId(), sender.getBalance(), sender.getCurrency());
    }

    private TransferResponse replay(UUID userId, String idemKey, String requestHash) {
        IdempotencyKey existing = idempotency.findByUserIdAndIdemKey(userId, idemKey)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Request in progress, retry shortly"));
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Idempotency-Key was already used with a different request");
        }
        if (existing.getTransactionId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Request in progress, retry shortly");
        }
        return new TransferResponse(existing.getTransactionId(), existing.getBalanceAfter(), "INR");
    }

    private static String sha256(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}