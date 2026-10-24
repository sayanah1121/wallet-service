package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Part 2, category 7: isolation level, hot-row contention and recovery from 40P01 / 40001.
class ContentionRecoveryTest extends UnthrottledHttpTestBase {

    @Autowired DataSource dataSource;

    private static void lock(Connection c, UUID walletId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("select id from wallets where id = ? for update")) {
            ps.setObject(1, walletId);
            ps.executeQuery();
        }
    }

    @Test
    @DisplayName("P2 TC-2.1a: hot wallet under contention with an artificial 20 ms delay per ledger write")
    void hotWalletWithInjectedDelay_allSucceed() throws Exception {
        int n = 20;
        UUID hot = newUser(n * 1_000L);
        String hotToken = tokenOf(hot);
        String hotEmail = emailOf(hot);
        UUID[] others = new UUID[n];
        String[] tokens = new String[n];
        String[] emails = new String[n];
        for (int i = 0; i < n; i++) {
            others[i] = newUser(1_000);
            tokens[i] = tokenOf(others[i]);
            emails[i] = emailOf(others[i]);
        }

        // every ledger insert now sleeps inside the transaction, so row locks are held ~40 ms per transfer
        jdbc.execute("""
                create or replace function test_slow_ledger() returns trigger as $$
                begin perform pg_sleep(0.02); return new; end $$ language plpgsql
                """);
        jdbc.execute("drop trigger if exists test_slow_ledger on ledger_entries");
        jdbc.execute("""
                create trigger test_slow_ledger before insert on ledger_entries
                for each row execute function test_slow_ledger()
                """);
        List<Reply> replies;
        long elapsedMs;
        try {
            // n users pay the hot wallet while the hot wallet pays each of them back, all at once
            long start = System.nanoTime();
            replies = fireTogether(2 * n, i -> i % 2 == 0
                    ? transferRequest(tokens[i / 2], "hot-in-" + UUID.randomUUID(), hotEmail, 1_000).build()
                    : transferRequest(hotToken, "hot-out-" + UUID.randomUUID(), emails[i / 2], 1_000).build());
            elapsedMs = (System.nanoTime() - start) / 1_000_000;
        } finally {
            jdbc.execute("drop trigger if exists test_slow_ledger on ledger_entries");
            jdbc.execute("drop function if exists test_slow_ledger()");
        }

        String isolation = jdbc.queryForObject("show default_transaction_isolation", String.class);
        TestMetrics.record("P2-TC-2.1a", "concurrent_transfers", 2 * n);
        TestMetrics.record("P2-TC-2.1a", "injected_delay_per_ledger_insert_ms", 20);
        TestMetrics.record("P2-TC-2.1a", "succeeded_201", replies.stream().filter(r -> r.status() == 201).count());
        TestMetrics.record("P2-TC-2.1a", "server_errors_5xx", replies.stream().filter(r -> r.status() >= 500).count());
        TestMetrics.record("P2-TC-2.1a", "elapsed_ms", elapsedMs);
        TestMetrics.record("P2-TC-2.1a", "isolation_level", isolation);

        assertThat(replies).allSatisfy(r -> assertThat(r.status()).as(r.body()).isEqualTo(201));
        assertThat(balanceOf(hot)).isEqualTo(n * 1_000L);
        for (UUID o : others) {
            assertThat(balanceOf(o)).isEqualTo(1_000);
        }
        assertWalletMatchesLedger(hot);
        assertLedgerBalanced();
        assertThat(isolation).isEqualTo("read committed");
    }

    @Test
    @DisplayName("P2 TC-2.1b: a real PostgreSQL deadlock (40P01) is retried and the transfer succeeds")
    void forcedDeadlock_isRetriedNot500() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        UUID wa = walletIdOf(a);
        UUID wb = walletIdOf(b);
        // TransferService locks in Java UUID order; take the second lock first to build a cycle
        UUID first = wa.compareTo(wb) < 0 ? wa : wb;
        UUID second = first.equals(wa) ? wb : wa;
        String deadlockTimeout = jdbc.queryForObject("show deadlock_timeout", String.class);

        HttpResponse<String> response;
        String externalOutcome;
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            lock(other, second);

            CompletableFuture<HttpResponse<String>> transfer = http.sendAsync(
                    transferRequest(token, "deadlock-" + UUID.randomUUID(), emailOf(b), 1_000).build(),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(500); // the app now holds `first` and waits for `second`

            // closing the cycle: PostgreSQL aborts the transaction that has waited longest, i.e. the app's
            CompletableFuture<String> closing = CompletableFuture.supplyAsync(() -> {
                try {
                    lock(other, first);
                    return "acquired";
                } catch (Exception e) {
                    return "aborted: " + e.getMessage();
                }
            });
            externalOutcome = closing.get(30, TimeUnit.SECONDS);
            other.rollback(); // release both locks so a retry by the app can go through
            response = transfer.get(30, TimeUnit.SECONDS);
        }

        TestMetrics.record("P2-TC-2.1b", "deadlock_timeout", deadlockTimeout);
        TestMetrics.record("P2-TC-2.1b", "deadlock_victim", externalOutcome.equals("acquired") ? "application" : "test connection");
        TestMetrics.record("P2-TC-2.1b", "api_status", response.statusCode());

        assertThat(externalOutcome).as("test setup: the application transaction must be the deadlock victim")
                .isEqualTo("acquired");
        assertThat(response.statusCode())
                .as("application should retry 40P01 with backoff instead of failing; got body %s", response.body())
                .isEqualTo(201);
        assertThat(balanceOf(a)).isEqualTo(9_000);
        assertThat(balanceOf(b)).isEqualTo(1_000);
        assertLedgerBalanced();
    }
}
