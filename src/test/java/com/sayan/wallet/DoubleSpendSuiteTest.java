package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Category 1: concurrency and race conditions, driven through POST /api/v1/transfers.
class DoubleSpendSuiteTest extends UnthrottledHttpTestBase {

    private static long count(List<Reply> replies, int status) {
        return replies.stream().filter(r -> r.status() == status).count();
    }

    @Test
    @DisplayName("TC-1.1a: 50 concurrent transfers drain a 100.00 balance exactly")
    void concurrentDrain_exactlyAffordableTransfersSucceed() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);

        long start = System.nanoTime();
        List<Reply> replies = fireTogether(50, i ->
                transferRequest(token, "drain-" + UUID.randomUUID(), toB, 1_000).build());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(count(replies, 201)).isEqualTo(10);
        assertThat(count(replies, 422)).isEqualTo(40);
        assertThat(replies).allMatch(r -> r.status() == 201 || r.status() == 422);
        assertThat(balanceOf(a)).isZero();
        assertThat(balanceOf(b)).isEqualTo(10_000);
        assertWalletMatchesLedger(a, b);
        assertLedgerBalanced();
        TestMetrics.record("TC-1.1a", "requests", 50);
        TestMetrics.record("TC-1.1a", "succeeded_201", count(replies, 201));
        TestMetrics.record("TC-1.1a", "rejected_422", count(replies, 422));
        TestMetrics.record("TC-1.1a", "elapsed_ms", elapsedMs);
    }

    @Test
    @DisplayName("TC-1.1b: 50 A->B and 50 B->A transfers at once: no deadlock, no overdraft")
    void opposingDirections_noDeadlockNoNegativeBalance() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String tokenA = tokenOf(a);
        String tokenB = tokenOf(b);
        String toA = emailOf(a);
        String toB = emailOf(b);

        // even index: A pays B, odd index: B pays A (B starts empty, so it can only spend what it receives)
        long start = System.nanoTime();
        List<Reply> replies = fireTogether(100, i -> i % 2 == 0
                ? transferRequest(tokenA, "ab-" + UUID.randomUUID(), toB, 1_000).build()
                : transferRequest(tokenB, "ba-" + UUID.randomUUID(), toA, 1_000).build());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long abOk = 0;
        long baOk = 0;
        for (int i = 0; i < replies.size(); i++) {
            Reply r = replies.get(i);
            // a deadlock or lock timeout would surface as a 5xx
            assertThat(r.status()).as("reply %d: %s", i, r.body()).isIn(201, 422);
            if (r.status() == 422) {
                assertThat(r.body()).contains("Insufficient funds");
            } else if (i % 2 == 0) {
                abOk++;
            } else {
                baOk++;
            }
        }

        assertThat(balanceOf(a)).isEqualTo(10_000 - 1_000 * abOk + 1_000 * baOk).isNotNegative();
        assertThat(balanceOf(b)).isEqualTo(1_000 * abOk - 1_000 * baOk).isNotNegative();
        assertThat(balanceOf(a) + balanceOf(b)).isEqualTo(10_000);
        assertWalletMatchesLedger(a, b);
        assertLedgerBalanced();
        TestMetrics.record("TC-1.1b", "requests", 100);
        TestMetrics.record("TC-1.1b", "a_to_b_succeeded", abOk);
        TestMetrics.record("TC-1.1b", "b_to_a_succeeded", baOk);
        TestMetrics.record("TC-1.1b", "rejected_422", count(replies, 422));
        TestMetrics.record("TC-1.1b", "server_errors_5xx",
                replies.stream().filter(r -> r.status() >= 500).count());
        TestMetrics.record("TC-1.1b", "elapsed_ms", elapsedMs);
    }

    @Test
    @DisplayName("TC-1.1c: three-wallet ring A->B->C->A under load does not deadlock")
    void ringOfThreeWallets_noDeadlock() throws Exception {
        UUID[] u = {newUser(5_000), newUser(5_000), newUser(5_000)};
        String[] tokens = {tokenOf(u[0]), tokenOf(u[1]), tokenOf(u[2])};
        String[] emails = {emailOf(u[0]), emailOf(u[1]), emailOf(u[2])};

        List<Reply> replies = fireTogether(60, i -> transferRequest(
                tokens[i % 3], "ring-" + UUID.randomUUID(), emails[(i + 1) % 3], 100).build());

        // every wallet pays 2000 and receives 2000, and never needs more than it started with
        assertThat(replies).allMatch(r -> r.status() == 201);
        for (UUID id : u) {
            assertThat(balanceOf(id)).isEqualTo(5_000);
        }
        assertWalletMatchesLedger(u);
        assertLedgerBalanced();
        TestMetrics.record("TC-1.1c", "requests", 60);
        TestMetrics.record("TC-1.1c", "succeeded_201", count(replies, 201));
    }

    @Test
    @DisplayName("TC-1.2: 100 simultaneous requests with one Idempotency-Key move money once")
    void idempotencyFlood_executesOnceAndReplaysSamePayload() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);
        String key = "flood-" + UUID.randomUUID();

        long start = System.nanoTime();
        List<Reply> replies = fireTogether(100, i -> transferRequest(token, key, toB, 1_000).build());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(replies).allMatch(r -> r.status() == 201);
        Set<String> bodies = replies.stream().map(Reply::body).collect(Collectors.toSet());
        assertThat(bodies).as("every reply carries the identical payload").hasSize(1);
        assertThat(replies.get(0).json().get("balance").asLong()).isEqualTo(9_000);

        // the transfer logic ran exactly once
        verify(events, times(1)).publishAfterCommit(any());
        assertThat(transferCountFrom(a)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, a, key)).isEqualTo(1);
        assertThat(balanceOf(a)).isEqualTo(9_000);
        assertThat(balanceOf(b)).isEqualTo(1_000);
        assertWalletMatchesLedger(a, b);
        assertLedgerBalanced();

        // a late retry still gets the original payload, even after the balance has moved on
        assertThat(send(transferRequest(token, "other-" + UUID.randomUUID(), toB, 500)).status()).isEqualTo(201);
        Reply lateRetry = send(transferRequest(token, key, toB, 1_000));
        assertThat(lateRetry.status()).isEqualTo(201);
        assertThat(lateRetry.body()).isEqualTo(replies.get(0).body());
        assertThat(balanceOf(a)).isEqualTo(8_500);

        TestMetrics.record("TC-1.2", "requests", 100);
        TestMetrics.record("TC-1.2", "distinct_payloads", bodies.size());
        TestMetrics.record("TC-1.2", "transfers_executed", 1);
        TestMetrics.record("TC-1.2", "elapsed_ms", elapsedMs);
    }
}
