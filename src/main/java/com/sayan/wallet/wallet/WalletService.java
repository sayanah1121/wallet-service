package com.sayan.wallet.wallet;

import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.common.util.PageResponse;
import com.sayan.wallet.ledger.Direction;
import com.sayan.wallet.ledger.LedgerEntry;
import com.sayan.wallet.ledger.LedgerEntryRepository;
import com.sayan.wallet.transaction.PaymentTransaction;
import com.sayan.wallet.transaction.PaymentTransactionRepository;
import com.sayan.wallet.transaction.TransactionStatus;
import com.sayan.wallet.transaction.TransactionType;
import com.sayan.wallet.wallet.WalletDtos.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sayan.wallet.cache.WalletCache;

@Service
@RequiredArgsConstructor
public class WalletService {
    public static final UUID SYSTEM_WALLET_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final WalletRepository wallets;
    private final PaymentTransactionRepository transactions;
    private final LedgerEntryRepository ledger;
    private final WalletCache cache;

    @Transactional
    public void createUserWallet(UUID userId) {
        Wallet w = new Wallet();
        w.setUserId(userId);
        wallets.save(w);
    }

       @Transactional(readOnly = true)
    public WalletResponse getMyWallet(UUID userId) {
        return cache.get(userId).orElseGet(() -> {
            Wallet w = wallets.findByUserId(userId).orElseThrow(this::walletNotFound);
            WalletResponse response = new WalletResponse(w.getId(), w.getBalance(), w.getCurrency());
            cache.put(userId, response);
            return response;
        });
    }

    @Transactional
    public TopUpResponse topUp(UUID userId, long amount) {
        UUID userWalletId = wallets.findIdByUserId(userId).orElseThrow(this::walletNotFound);

        Map<UUID, Wallet> locked = lockInOrder(userWalletId, SYSTEM_WALLET_ID);
        Wallet user = locked.get(userWalletId);
        Wallet system = locked.get(SYSTEM_WALLET_ID);

        PaymentTransaction tx = new PaymentTransaction();
        tx.setType(TransactionType.TOPUP);
        tx.setStatus(TransactionStatus.COMPLETED);
        tx.setAmount(amount);
        tx.setCurrency(user.getCurrency());
        tx.setDescription("Wallet top-up");
        transactions.save(tx);

        system.setBalance(Math.subtractExact(system.getBalance(), amount));
        user.setBalance(Math.addExact(user.getBalance(), amount));

        ledger.save(entry(tx.getId(), system.getId(), Direction.DEBIT, amount, system.getBalance()));
        ledger.save(entry(tx.getId(), user.getId(), Direction.CREDIT, amount, user.getBalance()));
        cache.evictAfterCommit(userId);

        return new TopUpResponse(tx.getId(), user.getBalance(), user.getCurrency());
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> history(UUID userId, int page, int size) {
        UUID walletId = wallets.findIdByUserId(userId).orElseThrow(this::walletNotFound);
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        Page<LedgerEntry> result = ledger.findByWalletIdOrderByCreatedAtDesc(walletId, pr);
        return PageResponse.from(result.map(LedgerEntryResponse::from));
    }

    // Always lock wallets in the same (sorted id) order so two concurrent
    // transactions can never wait on each other in a cycle (deadlock).
    public Map<UUID, Wallet> lockInOrder(UUID a, UUID b) {
        Map<UUID, Wallet> locked = new HashMap<>();
        for (UUID id : Stream.of(a, b).sorted().toList()) {
            locked.put(id, wallets.findByIdForUpdate(id).orElseThrow(this::walletNotFound));
        }
        return locked;
    }

    public LedgerEntry entry(UUID txId, UUID walletId, Direction dir, long amount, long balanceAfter) {
        LedgerEntry e = new LedgerEntry();
        e.setTransactionId(txId);
        e.setWalletId(walletId);
        e.setDirection(dir);
        e.setAmount(amount);
        e.setBalanceAfter(balanceAfter);
        return e;
    }

    private ApiException walletNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "Wallet not found");
    }
}