package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.reconciliation.ReconciliationService;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

// Part 2, category 8: money is an integer number of paise end to end (Java long, PostgreSQL BIGINT).
class PrecisionBoundaryTest extends UnthrottledHttpTestBase {

    @Autowired ReconciliationService reconciliation;

    private String rawTransfer(String token, String amountLiteral, String toEmail) {
        return "{\"toEmail\":\"%s\",\"amount\":%s}".formatted(toEmail, amountLiteral);
    }

    @Test
    @DisplayName("P2 TC-3.1a: 33.33 transferred three times from 99.99 leaves exactly 0.00, no lost paise")
    void thirtyThreeThirtyThreeThreeTimes() throws Exception {
        UUID a = newUser(9_999);
        UUID[] r = {newUser(0), newUser(0), newUser(0)};
        String token = tokenOf(a);

        for (UUID to : r) {
            assertThat(send(transferRequest(token, "p-" + UUID.randomUUID(), emailOf(to), 3_333)).status())
                    .isEqualTo(201);
        }

        assertThat(balanceOf(a)).isZero();
        for (UUID to : r) {
            assertThat(balanceOf(to)).isEqualTo(3_333);
        }
        assertWalletMatchesLedger(a, r[0], r[1], r[2]);
        assertLedgerBalanced();
    }

    @Test
    @DisplayName("P2 TC-3.1b: 100.00 split three ways (33.33 + 33.33 + 33.34) and 200 one-paisa transfers stay exact")
    void splitsAndMicroTransfersStayExact() throws Exception {
        UUID a = newUser(10_000);
        UUID[] r = {newUser(0), newUser(0), newUser(0)};
        String token = tokenOf(a);
        long[] split = {3_333, 3_333, 3_334};
        for (int i = 0; i < 3; i++) {
            assertThat(send(transferRequest(token, "split-" + UUID.randomUUID(), emailOf(r[i]), split[i])).status())
                    .isEqualTo(201);
        }
        assertThat(balanceOf(a)).isZero();

        // 200 concurrent transfers of the smallest unit
        UUID micro = newUser(200);
        String microToken = tokenOf(micro);
        String to = emailOf(r[0]);
        List<Reply> replies = fireTogether(200, i -> transferRequest(microToken, "paisa-" + UUID.randomUUID(), to, 1).build());

        assertThat(replies).allMatch(x -> x.status() == 201);
        assertThat(balanceOf(micro)).isZero();
        assertThat(balanceOf(r[0])).isEqualTo(3_533);
        assertThat(balanceOf(r[0]) + balanceOf(r[1]) + balanceOf(r[2])).isEqualTo(10_200);
        assertWalletMatchesLedger(a, micro, r[0], r[1], r[2]);

        ReconciliationService.RunSummary run = reconciliation.run("MANUAL").orElseThrow();
        assertThat(run.status()).isEqualTo("OK");
        TestMetrics.record("P2-TC-3.1b", "one_paisa_transfers", 200);
        TestMetrics.record("P2-TC-3.1b", "reconciliation_status", run.status());
    }

    @Test
    @DisplayName("P2 TC-3.1c: fractional amounts in JSON (33.33, 0.5, 1e-2) are rejected, never truncated")
    void fractionalAmounts_areRejectedNotTruncated() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        Map<String, Integer> statuses = new java.util.LinkedHashMap<>();

        for (String literal : new String[] {"33.33", "0.5", "1e-2", "3333.999"}) {
            Reply reply = send(jsonPost("/api/v1/transfers", token, rawTransfer(token, literal, emailOf(b)))
                    .header("Idempotency-Key", "frac-" + UUID.randomUUID()));
            statuses.put(literal, reply.status());
            TestMetrics.record("P2-TC-3.1c", "status_for_amount_" + literal, reply.status());
        }
        long moved = 10_000 - balanceOf(a);
        TestMetrics.record("P2-TC-3.1c", "paise_moved_by_fractional_requests", moved);

        assertThat(statuses)
                .as("fractional amounts must be rejected; %d paise were moved after silent truncation", moved)
                .allSatisfy((literal, status) -> assertThat(status).as("amount %s", literal).isEqualTo(400));
        assertThat(balanceOf(a)).isEqualTo(10_000);
    }

    @Test
    @DisplayName("P2 TC-3.1d: amount boundaries: 1 paisa and the 100,000.00 maximum accepted; max+1 and >Long.MAX rejected")
    void amountBoundaries() throws Exception {
        UUID a = newUser(10_000_000);
        walletService.topUp(a, 1);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String toB = emailOf(b);

        assertThat(send(transferRequest(token, "min-" + UUID.randomUUID(), toB, 1)).status()).isEqualTo(201);
        assertThat(send(transferRequest(token, "max-" + UUID.randomUUID(), toB, 10_000_000)).status()).isEqualTo(201);
        assertThat(send(transferRequest(token, "over-" + UUID.randomUUID(), toB, 10_000_001)).status()).isEqualTo(400);
        int huge = send(jsonPost("/api/v1/transfers", token, rawTransfer(token, "9223372036854775808", toB))
                .header("Idempotency-Key", "huge-" + UUID.randomUUID())).status();
        assertThat(huge).as("number beyond Long.MAX_VALUE").isEqualTo(400);

        assertThat(balanceOf(a)).isZero();
        assertThat(balanceOf(b)).isEqualTo(10_000_001);
        assertWalletMatchesLedger(a, b);
    }

    @Test
    @DisplayName("P2 TC-3.1e: no float/double anywhere in the money model; every money column is BIGINT")
    void noFloatingPointInMoneyModel() throws IOException {
        Path classes = Path.of("target", "classes");
        List<String> floating = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(p).toString().replace('\\', '.').replace('/', '.')
                        .replaceAll("\\.class$", "");
                try {
                    for (Field f : Class.forName(name, false, getClass().getClassLoader()).getDeclaredFields()) {
                        Class<?> t = f.getType();
                        if (!Modifier.isStatic(f.getModifiers()) && (t == float.class || t == double.class
                                || t == Float.class || t == Double.class)) {
                            floating.add(name + "." + f.getName());
                        }
                    }
                } catch (Throwable ignored) {
                    // classes that cannot be loaded in isolation have no fields to inspect here
                }
            }
        }
        assertThat(floating).as("float/double fields").isEmpty();

        List<Map<String, Object>> columns = jdbc.queryForList("""
                select table_name, column_name, data_type from information_schema.columns
                where table_schema = 'public' and (table_name, column_name) in (
                  ('wallets','balance'), ('transactions','amount'), ('ledger_entries','amount'),
                  ('ledger_entries','balance_after'), ('idempotency_keys','balance_after'))
                """);
        assertThat(columns).hasSize(5).allSatisfy(c -> assertThat(c.get("data_type")).as(c.toString()).isEqualTo("bigint"));
        TestMetrics.record("P2-TC-3.1e", "money_columns_bigint", columns.size());
        TestMetrics.record("P2-TC-3.1e", "float_or_double_fields", floating.size());
    }
}
