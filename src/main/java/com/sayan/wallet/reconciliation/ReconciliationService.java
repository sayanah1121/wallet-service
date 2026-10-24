package com.sayan.wallet.reconciliation;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {
    private static final String LOCK_KEY = "lock:reconciliation";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    // delete the lock only if we still own it
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end
            """, Long.class);

    private static final String SIGNED_SUM =
            "SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)";

    // 1. wallet balance must equal the net of its ledger entries
    private static final String WALLET_MISMATCH_SQL = """
            SELECT w.id, w.balance, COALESCE(%s, 0) AS ledger_sum
            FROM wallets w
            LEFT JOIN ledger_entries l ON l.wallet_id = w.id
            GROUP BY w.id, w.balance
            HAVING w.balance <> COALESCE(%s, 0)
            """.formatted(SIGNED_SUM, SIGNED_SUM);

    // 2. every transaction must have exactly 2 entries that net to zero
    private static final String UNBALANCED_TX_SQL = """
            SELECT l.transaction_id, %s AS net, COUNT(*) AS entries
            FROM ledger_entries l
            GROUP BY l.transaction_id
            HAVING %s <> 0 OR COUNT(*) <> 2
            """.formatted(SIGNED_SUM, SIGNED_SUM);

    // 3. a completed transaction must have ledger entries
    private static final String ORPHAN_TX_SQL = """
            SELECT t.id
            FROM transactions t
            WHERE t.status = 'COMPLETED'
              AND NOT EXISTS (SELECT 1 FROM ledger_entries l WHERE l.transaction_id = t.id)
            """;

    // 4. the whole ledger must net to zero
    private static final String GRAND_TOTAL_SQL =
            "SELECT COALESCE(" + SIGNED_SUM + ", 0) FROM ledger_entries l";

    public record Issue(String check, String subjectId, String detail) {}

    public record RunSummary(UUID id, Instant startedAt, Instant finishedAt, String status,
                             String trigger, int walletsChecked, int transactionsChecked,
                             int issuesFound) {}

    private static final RowMapper<RunSummary> RUN_MAPPER = (rs, i) -> new RunSummary(
            rs.getObject("id", UUID.class),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("finished_at").toInstant(),
            rs.getString("status"),
            rs.getString("trigger_type"),
            rs.getInt("wallets_checked"),
            rs.getInt("transactions_checked"),
            rs.getInt("issues_found"));

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final TransactionTemplate tx;

    // Empty result means another instance already holds the lock
    public Optional<RunSummary> run(String trigger) {
        String token = UUID.randomUUID().toString();
        if (!acquire(token)) {
            return Optional.empty();
        }
        try {
            return Optional.of(execute(trigger));
        } finally {
            release(token);
        }
    }

    public List<RunSummary> recentRuns() {
        return jdbc.query("""
                SELECT id, started_at, finished_at, status, trigger_type,
                       wallets_checked, transactions_checked, issues_found
                FROM reconciliation_runs ORDER BY started_at DESC LIMIT 10
                """, RUN_MAPPER);
    }

    public List<Issue> issues(UUID runId) {
        return jdbc.query("""
                SELECT check_name, subject_id, detail
                FROM reconciliation_issues WHERE run_id = ? ORDER BY check_name, subject_id
                """, (rs, i) -> new Issue(rs.getString("check_name"), rs.getString("subject_id"),
                rs.getString("detail")), runId);
    }

    private RunSummary execute(String trigger) {
        UUID runId = UUID.randomUUID();
        Instant started = Instant.now();
        List<Issue> issues = new ArrayList<>();

        issues.addAll(jdbc.query(WALLET_MISMATCH_SQL, (rs, i) -> new Issue(
                "WALLET_BALANCE_MISMATCH", rs.getString("id"),
                "balance=" + rs.getLong("balance") + " ledger_sum=" + rs.getLong("ledger_sum"))));

        issues.addAll(jdbc.query(UNBALANCED_TX_SQL, (rs, i) -> new Issue(
                "UNBALANCED_TRANSACTION", rs.getString("transaction_id"),
                "net=" + rs.getLong("net") + " entries=" + rs.getLong("entries"))));

        issues.addAll(jdbc.query(ORPHAN_TX_SQL, (rs, i) -> new Issue(
                "TRANSACTION_WITHOUT_LEDGER", rs.getString("id"), "no ledger entries")));

        Long grandTotal = jdbc.queryForObject(GRAND_TOTAL_SQL, Long.class);
        if (grandTotal != null && grandTotal != 0) {
            issues.add(new Issue("LEDGER_NOT_ZERO_SUM", "ledger", "net=" + grandTotal));
        }

        int wallets = jdbc.queryForObject("SELECT COUNT(*) FROM wallets", Integer.class);
        int transactions = jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        Instant finished = Instant.now();
        String status = issues.isEmpty() ? "OK" : "MISMATCH";

        // save the run and its issues atomically
        tx.executeWithoutResult(s -> {
            jdbc.update("""
                    INSERT INTO reconciliation_runs
                    (id, started_at, finished_at, status, trigger_type,
                     wallets_checked, transactions_checked, issues_found)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, runId, Timestamp.from(started), Timestamp.from(finished), status, trigger,
                    wallets, transactions, issues.size());
            jdbc.batchUpdate("""
                    INSERT INTO reconciliation_issues (run_id, check_name, subject_id, detail)
                    VALUES (?, ?, ?, ?)
                    """, issues, 100, (ps, issue) -> {
                ps.setObject(1, runId);
                ps.setString(2, issue.check());
                ps.setString(3, issue.subjectId());
                ps.setString(4, issue.detail());
            });
        });

        if (!issues.isEmpty()) {
            log.error("Reconciliation {} found {} issue(s)", runId, issues.size());
        }
        return new RunSummary(runId, started, finished, status, trigger, wallets, transactions, issues.size());
    }

    private boolean acquire(String token) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL));
        } catch (Exception e) {
            log.warn("Reconciliation lock unavailable, running anyway: {}", e.getMessage());
            return true;
        }
    }

    private void release(String token) {
        try {
            redis.execute(RELEASE, List.of(LOCK_KEY), token);
        } catch (Exception e) {
            log.warn("Reconciliation lock release failed: {}", e.getMessage());
        }
    }
}