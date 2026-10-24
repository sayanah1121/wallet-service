package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.transaction.TransferDtos.TransferRequest;
import com.sayan.wallet.transaction.TransferService;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

class TransferConcurrencyTest extends IntegrationTestBase {

    @Autowired TransferService transfers;

    @Test
    void concurrentTransfers_neverOverdraw() throws Exception {
        UUID sender = newUser(10_000);
        UUID receiver = newUser(0);
        String to = emailOf(receiver);

        // 30 parallel transfers of 1000 against a balance that only covers 10 of them
        RaceResult result = race(30, i -> () ->
                transfers.transfer(sender, "race-" + UUID.randomUUID(), new TransferRequest(to, 1_000)));

        assertThat(result.succeeded()).hasSize(10);
        assertThat(result.rejected()).hasSize(20);
        assertThat(result.rejected())
                .allMatch(e -> e.getStatus() == HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf(sender)).isZero();
        assertThat(balanceOf(receiver)).isEqualTo(10_000);
        assertLedgerBalanced();
    }

    @Test
    void sameIdempotencyKey_movesMoneyOnce() throws Exception {
        UUID sender = newUser(10_000);
        UUID receiver = newUser(0);
        String to = emailOf(receiver);
        String key = "same-" + UUID.randomUUID();

        // a client retry storm: 10 identical requests at the same instant
        RaceResult result = race(10, i -> () ->
                transfers.transfer(sender, key, new TransferRequest(to, 1_000)));

        assertThat(result.rejected()).isEmpty();
        assertThat(result.succeeded().stream().map(r -> r.transactionId()).collect(Collectors.toSet()))
                .hasSize(1);
        assertThat(balanceOf(sender)).isEqualTo(9_000);
        assertThat(balanceOf(receiver)).isEqualTo(1_000);
        assertLedgerBalanced();
    }

    @Test
    void opposingTransfers_doNotDeadlock() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(10_000);
        String toA = emailOf(a);
        String toB = emailOf(b);

        // half go A to B, half go B to A, all at once; sorted lock order prevents deadlock
        RaceResult result = race(20, i -> () -> i % 2 == 0
                ? transfers.transfer(a, "ab-" + UUID.randomUUID(), new TransferRequest(toB, 100))
                : transfers.transfer(b, "ba-" + UUID.randomUUID(), new TransferRequest(toA, 100)));

        assertThat(result.rejected()).isEmpty();
        assertThat(result.succeeded()).hasSize(20);
        assertThat(balanceOf(a)).isEqualTo(10_000);
        assertThat(balanceOf(b)).isEqualTo(10_000);
        assertLedgerBalanced();
    }

    @Test
    void reusedKeyWithDifferentBody_isRejected() {
        UUID sender = newUser(10_000);
        UUID receiver = newUser(0);
        String to = emailOf(receiver);
        String key = "reuse-" + UUID.randomUUID();

        transfers.transfer(sender, key, new TransferRequest(to, 100));
        ApiException error = expectApiError(
                () -> transfers.transfer(sender, key, new TransferRequest(to, 200)));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf(sender)).isEqualTo(9_900);
        assertLedgerBalanced();
    }

    @Test
    void insufficientFunds_isRejectedAndLeavesBalancesUntouched() {
        UUID sender = newUser(500);
        UUID receiver = newUser(0);
        String to = emailOf(receiver);

        ApiException error = expectApiError(
                () -> transfers.transfer(sender, "poor-" + UUID.randomUUID(), new TransferRequest(to, 1_000)));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf(sender)).isEqualTo(500);
        assertThat(balanceOf(receiver)).isZero();
        assertLedgerBalanced();
    }

    @Test
    void transferToSelf_isRejected() {
        UUID user = newUser(1_000);

        ApiException error = expectApiError(
                () -> transfers.transfer(user, "self-" + UUID.randomUUID(),
                        new TransferRequest(emailOf(user), 100)));

        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(user)).isEqualTo(1_000);
        assertLedgerBalanced();
    }
}