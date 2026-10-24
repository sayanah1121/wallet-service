package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Category 3.1: the broker itself is unreachable. bootstrap-servers points at a closed port, and
// max.block.ms is lowered from its 60s default so the test runs in reasonable time.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.producer.properties.max.block.ms=" + KafkaOutageTest.MAX_BLOCK_MS
})
class KafkaOutageTest extends IntegrationTestSupport {
    static final int MAX_BLOCK_MS = 3_000;

    @Test
    @DisplayName("TC-3.1e: Kafka broker down -> POST /transfers still commits and returns 201")
    void brokerDown_transferStillSucceeds() throws Exception {
        UUID a = newUser(10_000);
        UUID b = newUser(0);
        String token = tokenOf(a);
        String key = "outage-" + UUID.randomUUID();

        long start = System.nanoTime();
        Reply reply = send(transferRequest(token, key, emailOf(b), 1_000));
        long latencyMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.json().get("balance").asLong()).isEqualTo(9_000);
        assertThat(balanceOf(a)).isEqualTo(9_000);
        assertThat(balanceOf(b)).isEqualTo(1_000);
        assertWalletMatchesLedger(a, b);
        assertLedgerBalanced();
        // the response is delayed by the producer's metadata wait, but bounded by max.block.ms
        assertThat(latencyMs).isLessThan(MAX_BLOCK_MS + 5_000L);

        // idempotent retry still works during the outage
        Reply retry = send(transferRequest(token, key, emailOf(b), 1_000));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(reply.body());
        assertThat(balanceOf(a)).isEqualTo(9_000);

        TestMetrics.record("TC-3.1e", "max_block_ms", MAX_BLOCK_MS);
        TestMetrics.record("TC-3.1e", "transfer_latency_during_outage_ms", latencyMs);
    }

    @Test
    @DisplayName("TC-3.1f: 20 concurrent transfers during a broker outage all commit")
    void brokerDown_concurrentTransfersAllCommit() throws Exception {
        int n = 20;
        UUID merchant = newUser(0);
        String to = emailOf(merchant);
        String[] tokens = new String[n];
        for (int i = 0; i < n; i++) {
            tokens[i] = tokenOf(newUser(1_000));
        }

        long start = System.nanoTime();
        List<Reply> replies = fireTogether(n, i ->
                transferRequest(tokens[i], "outage-" + UUID.randomUUID(), to, 1_000).build());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(replies).allMatch(r -> r.status() == 201);
        assertThat(balanceOf(merchant)).isEqualTo(n * 1_000L);
        assertLedgerBalanced();
        TestMetrics.record("TC-3.1f", "concurrent_transfers", n);
        TestMetrics.record("TC-3.1f", "elapsed_ms", elapsedMs);
        TestMetrics.record("TC-3.1f", "max_block_ms", MAX_BLOCK_MS);
    }
}
