package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.transaction.TransferDtos.TransferRequest;
import com.sayan.wallet.transaction.TransferDtos.TransferResponse;
import com.sayan.wallet.transaction.TransferService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;

// Category 4: financial integrity. A chaotic mix of operations, then the reconciliation job.
class LedgerIntegrityTest extends HttpTestBase {

    // scaled down from the 1-hour soak in the test plan; raise with -Dchaos.seconds=3600
    private static final int CHAOS_SECONDS = Integer.getInteger("chaos.seconds", 20);
    private static final int WORKERS = 16;
    private static final int USERS = 20;

    @Autowired TransferService transfers;
    @Autowired StringRedisTemplate redis;

    private record Done(String key, UUID sender, String toEmail, long amount) {}

    private Reply runReconciliation(String adminToken) throws Exception {
        return send(jsonPost("/api/v1/admin/reconciliation/runs", adminToken, ""));
    }

    @Test
    @DisplayName("TC-4.1: chaotic top-ups/transfers/failures, then reconciliation reports zero discrepancies")
    void chaosThenReconciliation_reportsNoDiscrepancies() throws Exception {
        List<UUID> people = new ArrayList<>();
        Map<UUID, AtomicLong> expected = new ConcurrentHashMap<>();
        for (int i = 0; i < USERS; i++) {
            UUID id = newUser(20_000);
            people.add(id);
            expected.put(id, new AtomicLong(20_000));
        }
        Map<UUID, String> emails = new ConcurrentHashMap<>();
        people.forEach(id -> emails.put(id, emailOf(id)));

        ConcurrentLinkedQueue<Done> completed = new ConcurrentLinkedQueue<>();
        Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(CHAOS_SECONDS).toNanos();

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            workers.add(pool.submit(() -> {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.nanoTime() < deadline) {
                    UUID me = people.get(rnd.nextInt(USERS));
                    UUID other = people.get(rnd.nextInt(USERS));
                    int dice = rnd.nextInt(100);
                    String op = "";
                    try {
                        if (dice < 20) {
                            op = "topup";
                            long amount = rnd.nextLong(1, 5_000);
                            walletService.topUp(me, amount);
                            expected.get(me).addAndGet(amount);
                        } else if (dice < 75) {
                            op = "transfer";
                            if (me.equals(other)) {
                                op = "self_transfer";
                            }
                            long amount = rnd.nextLong(1, 8_000);
                            String key = "chaos-" + UUID.randomUUID();
                            transfers.transfer(me, key, new TransferRequest(emails.get(other), amount));
                            expected.get(me).addAndGet(-amount);
                            expected.get(other).addAndGet(amount);
                            completed.add(new Done(key, me, emails.get(other), amount));
                        } else if (dice < 82) {
                            op = "unknown_recipient";
                            transfers.transfer(me, "chaos-" + UUID.randomUUID(),
                                    new TransferRequest("nobody-" + UUID.randomUUID() + "@test.com", 100));
                        } else if (dice < 92) {
                            Done d = completed.peek();
                            op = d == null ? "skipped" : "replay";
                            if (d != null) {
                                transfers.transfer(d.sender(), d.key(), new TransferRequest(d.toEmail(), d.amount()));
                            }
                        } else {
                            Done d = completed.peek();
                            op = d == null ? "skipped" : "key_reuse_different_body";
                            if (d != null) {
                                transfers.transfer(d.sender(), d.key(),
                                        new TransferRequest(d.toEmail(), d.amount() + 1));
                            }
                        }
                        outcomes.computeIfAbsent(op + ":ok", k -> new LongAdder()).increment();
                    } catch (ApiException e) {
                        outcomes.computeIfAbsent(op + ":" + e.getStatus().value(), k -> new LongAdder()).increment();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                }
            }));
        }
        for (Future<?> f : workers) {
            f.get();
        }
        pool.shutdown();

        assertThat(unexpected).as("unexpected (non-business) failures").isEmpty();
        // the model computed from successful operations matches the database for every wallet
        for (UUID id : people) {
            assertThat(balanceOf(id)).as("balance of %s", id).isEqualTo(expected.get(id).get()).isNotNegative();
        }
        assertWalletMatchesLedger(people.toArray(UUID[]::new));
        // no ledger entry for a user wallet ever recorded a negative running balance
        assertThat(jdbc.queryForObject("""
                select count(*) from ledger_entries l join wallets w on w.id = l.wallet_id
                where w.type = 'USER' and l.balance_after < 0
                """, Integer.class)).isZero();
        // user money in the system equals what the system wallet issued through top-ups
        assertThat(jdbc.queryForObject("select sum(balance) from wallets", Long.class)).isZero();

        // trigger the nightly job manually through the admin API
        Reply run = runReconciliation(adminToken());
        assertThat(run.status()).isEqualTo(200);
        JsonNode summary = run.json();
        assertThat(summary.get("status").asString()).isEqualTo("OK");
        assertThat(summary.get("issuesFound").asInt()).isZero();

        long ops = outcomes.values().stream().mapToLong(LongAdder::sum).sum();
        TestMetrics.record("TC-4.1", "duration_s", CHAOS_SECONDS);
        TestMetrics.record("TC-4.1", "workers", WORKERS);
        TestMetrics.record("TC-4.1", "users", USERS);
        TestMetrics.record("TC-4.1", "operations", ops);
        TestMetrics.record("TC-4.1", "ops_per_second", ops / CHAOS_SECONDS);
        outcomes.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> TestMetrics.record("TC-4.1", "outcome." + e.getKey(), e.getValue().sum()));
        TestMetrics.record("TC-4.1", "reconciliation_status", summary.get("status").asString());
        TestMetrics.record("TC-4.1", "reconciliation_issues", summary.get("issuesFound").asInt());
        TestMetrics.record("TC-4.1", "wallets_checked", summary.get("walletsChecked").asInt());
        TestMetrics.record("TC-4.1", "transactions_checked", summary.get("transactionsChecked").asInt());
    }

    @Test
    @DisplayName("TC-4.2: reconciliation detects an orphan transaction and an unbalanced ledger entry")
    void reconciliation_detectsInjectedCorruption() throws Exception {
        UUID sender = newUser(5_000);
        UUID receiver = newUser(0);
        TransferResponse t = transfers.transfer(sender, "corrupt-" + UUID.randomUUID(),
                new TransferRequest(emailOf(receiver), 1_000));
        UUID orphan = UUID.randomUUID();
        UUID extraEntry = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into transactions (id, type, status, amount, currency, created_at)
                    values (?, 'TRANSFER', 'COMPLETED', 100, 'INR', now())
                    """, orphan);
            jdbc.update("""
                    insert into ledger_entries (id, transaction_id, wallet_id, direction, amount, balance_after)
                    values (?, ?, ?, 'CREDIT', 1, 0)
                    """, extraEntry, t.transactionId(), walletIdOf(receiver));

            Reply run = runReconciliation(adminToken());
            assertThat(run.json().get("status").asString()).isEqualTo("MISMATCH");
            String runId = run.json().get("id").asString();
            Reply issues = send(get("/api/v1/admin/reconciliation/runs/" + runId + "/issues", adminToken()));
            List<String> checks = new ArrayList<>();
            issues.json().forEach(i -> checks.add(i.get("check").asString() + ":" + i.get("subjectId").asString()));

            assertThat(checks).contains(
                    "TRANSACTION_WITHOUT_LEDGER:" + orphan,
                    "UNBALANCED_TRANSACTION:" + t.transactionId(),
                    "WALLET_BALANCE_MISMATCH:" + walletIdOf(receiver),
                    "LEDGER_NOT_ZERO_SUM:ledger");
            TestMetrics.record("TC-4.2", "issues_detected", checks.size());
        } finally {
            jdbc.update("delete from ledger_entries where id = ?", extraEntry);
            jdbc.update("delete from transactions where id = ?", orphan);
        }
        assertLedgerBalanced();
    }

    @Test
    @DisplayName("TC-4.3: a second reconciliation run is refused while one holds the Redis lock")
    void concurrentRun_isRefused() throws Exception {
        String admin = adminToken();
        String token = "held-by-test-" + UUID.randomUUID();
        redis.opsForValue().set("lock:reconciliation", token, Duration.ofSeconds(30));
        try {
            Reply run = runReconciliation(admin);
            assertThat(run.status()).isEqualTo(409);
        } finally {
            if (token.equals(redis.opsForValue().get("lock:reconciliation"))) {
                redis.delete("lock:reconciliation");
            }
        }
        assertThat(runReconciliation(admin).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("TC-4.4: invalid amounts (0, negative, above max) are rejected and never touch the ledger")
    void invalidAmounts_neverReachTheLedger() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        long entriesBefore = jdbc.queryForObject("select count(*) from ledger_entries", Long.class);

        for (long amount : new long[] {0, -500, 10_000_001}) {
            assertThat(send(transferRequest(token, "bad-" + UUID.randomUUID(), emailOf(b), amount)).status())
                    .as("amount %d", amount).isEqualTo(400);
        }
        assertThat(send(jsonPost("/api/v1/wallets/topup", token, "{\"amount\":-1}")).status()).isEqualTo(400);

        assertThat(balanceOf(a)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries", Long.class)).isEqualTo(entriesBefore);
    }
}
