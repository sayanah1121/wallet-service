package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Category 2: idempotency edge cases.
class IdempotencyEdgeCaseTest extends UnthrottledHttpTestBase {

    @Autowired DataSource dataSource;

    private UUID committedTransactionFor(UUID userId, String key) {
        return jdbc.queryForObject(
                "select transaction_id from idempotency_keys where user_id = ? and idem_key = ?",
                UUID.class, userId, key);
    }

    private boolean isCommitted(UUID userId, String key) {
        return jdbc.queryForObject("""
                select count(*) from idempotency_keys
                where user_id = ? and idem_key = ? and transaction_id is not null
                """, Integer.class, userId, key) == 1;
    }

    // Holds a row lock on the wallet from a separate connection, so a transfer touching it stalls.
    private Connection lockWallet(UUID walletId) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("select id from wallets where id = ? for update")) {
            ps.setObject(1, walletId);
            ps.executeQuery();
        }
        return c;
    }

    @Test
    @DisplayName("TC-2.1a: client times out after the server commits; retry returns the original result")
    void clientTimeoutAfterCommit_retryReturnsOriginalResult() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);
        String key = "timeout-" + UUID.randomUUID();

        try (Connection blocker = lockWallet(walletIdOf(b))) {
            // the server stalls on the lock, so the client gives up after 1s
            assertThatThrownBy(() -> send(transferRequest(token, key, toB, 1_000)
                    .timeout(Duration.ofSeconds(1))))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(balanceOf(a)).isEqualTo(10_000);

            // releasing the lock lets the abandoned request finish and commit on the server
            blocker.commit();
        }
        await(Duration.ofSeconds(15), "abandoned request to commit", () -> isCommitted(a, key));
        UUID committedTx = committedTransactionFor(a, key);

        Reply retry = send(transferRequest(token, key, toB, 1_000));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.json().get("transactionId").asString()).isEqualTo(committedTx.toString());
        assertThat(retry.json().get("balance").asLong()).isEqualTo(9_000);
        assertThat(transferCountFrom(a)).isEqualTo(1);
        assertThat(balanceOf(a)).isEqualTo(9_000);
        assertThat(balanceOf(b)).isEqualTo(1_000);
        assertWalletMatchesLedger(a, b);
        assertLedgerBalanced();
    }

    @Test
    @DisplayName("TC-2.1b: retry sent while the original is still in flight waits and gets the same result")
    void retryWhileOriginalInFlight_getsSameResult() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);
        String key = "inflight-" + UUID.randomUUID();

        CompletableFuture<HttpResponse<String>> original;
        CompletableFuture<HttpResponse<String>> retry;
        try (Connection blocker = lockWallet(walletIdOf(b))) {
            original = http.sendAsync(transferRequest(token, key, toB, 1_000).build(),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(500);
            retry = http.sendAsync(transferRequest(token, key, toB, 1_000).build(),
                    HttpResponse.BodyHandlers.ofString());
            Thread.sleep(500);
            assertThat(original).isNotDone();
            assertThat(retry).isNotDone();
            blocker.commit();
        }

        HttpResponse<String> first = original.get();
        HttpResponse<String> second = retry.get();
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(transferCountFrom(a)).isEqualTo(1);
        assertThat(balanceOf(a)).isEqualTo(9_000);
        assertLedgerBalanced();
    }

    @Test
    @DisplayName("TC-2.1c: a request that fails before commit does not burn its key")
    void failedRequestRollsBackKey_retryExecutes() throws Exception {
        UUID a = newUser(500);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);
        String key = "rollback-" + UUID.randomUUID();

        // the transaction claims the key, then aborts on insufficient funds: everything rolls back
        Reply failed = send(transferRequest(token, key, toB, 1_000));
        assertThat(failed.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, a, key)).isZero();

        walletService.topUp(a, 1_000);
        Reply retry = send(transferRequest(token, key, toB, 1_000));

        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.json().get("balance").asLong()).isEqualTo(500);
        assertThat(balanceOf(b)).isEqualTo(1_000);
        assertThat(transferCountFrom(a)).isEqualTo(1);
        assertLedgerBalanced();
    }

    @Test
    @DisplayName("TC-2.2a: same key, amount 50.00 then 500.00 -> second request rejected")
    void sameKeyDifferentAmount_isRejected() throws Exception {
        UUID a = newUser(100_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);
        String key = "abc-123-" + UUID.randomUUID();

        Reply first = send(transferRequest(token, key, toB, 5_000));
        Reply second = send(transferRequest(token, key, toB, 50_000));

        assertThat(first.status()).isEqualTo(201);
        // 422 per the IETF Idempotency-Key draft: key reused with a different payload
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.json().get("detail").asString())
                .isEqualTo("Idempotency-Key was already used with a different request");
        assertThat(balanceOf(a)).isEqualTo(95_000);
        assertThat(balanceOf(b)).isEqualTo(5_000);
        assertThat(transferCountFrom(a)).isEqualTo(1);
        assertLedgerBalanced();
        TestMetrics.record("TC-2.2a", "second_request_status", second.status());
    }

    @Test
    @DisplayName("TC-2.2b: same key, same amount, different recipient -> rejected")
    void sameKeyDifferentRecipient_isRejected() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        UUID c = newUser(0);
        String token = tokenOf(a);
        String key = "recipient-" + UUID.randomUUID();

        assertThat(send(transferRequest(token, key, emailOf(b), 1_000)).status()).isEqualTo(201);
        assertThat(send(transferRequest(token, key, emailOf(c), 1_000)).status()).isEqualTo(422);
        assertThat(balanceOf(c)).isZero();
        assertThat(balanceOf(a)).isEqualTo(9_000);
    }

    @Test
    @DisplayName("TC-2.2c: recipient email differing only in letter case counts as the same request")
    void normalisedEmail_isTreatedAsSameRequest() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String key = "normalise-" + UUID.randomUUID();

        Reply first = send(transferRequest(token, key, emailOf(b), 1_000));
        Reply second = send(transferRequest(token, key, emailOf(b).toUpperCase(), 1_000));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(balanceOf(a)).isEqualTo(9_000);
    }

    @Test
    @DisplayName("TC-2.2d: the same key used by two different users is independent")
    void keysAreScopedPerUser() throws Exception {
        UUID a = newUser(10_000);
        UUID c = newUser(10_000);
        UUID b = newUser(0);
        String key = "shared-" + UUID.randomUUID();

        Reply fromA = send(transferRequest(tokenOf(a), key, emailOf(b), 1_000));
        Reply fromC = send(transferRequest(tokenOf(c), key, emailOf(b), 1_000));

        assertThat(fromA.status()).isEqualTo(201);
        assertThat(fromC.status()).isEqualTo(201);
        assertThat(fromC.json().get("transactionId")).isNotEqualTo(fromA.json().get("transactionId"));
        assertThat(balanceOf(b)).isEqualTo(2_000);
    }

    @Test
    @DisplayName("TC-2.2e: missing, blank or over-long Idempotency-Key is rejected with 400")
    void invalidKeyHeader_isRejected() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);

        assertThat(send(transferRequest(token, null, toB, 1_000)).status()).isEqualTo(400);
        assertThat(send(transferRequest(token, " ", toB, 1_000)).status()).isEqualTo(400);
        assertThat(send(transferRequest(token, "k".repeat(101), toB, 1_000)).status()).isEqualTo(400);
        assertThat(balanceOf(a)).isEqualTo(10_000);
    }
}
