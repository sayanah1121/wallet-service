package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.reconciliation.ReconciliationService;
import com.sayan.wallet.reconciliation.ReconciliationService.RunSummary;
import com.sayan.wallet.transaction.TransferDtos.TransferRequest;
import com.sayan.wallet.transaction.TransferService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationTest extends IntegrationTestBase {

    @Autowired ReconciliationService reconciliation;
    @Autowired TransferService transfers;

    @Test
    void cleanLedger_reportsOk() {
        UUID sender = newUser(5_000);
        UUID receiver = newUser(0);
        transfers.transfer(sender, "rec-" + UUID.randomUUID(),
                new TransferRequest(emailOf(receiver), 1_000));

        RunSummary run = reconciliation.run("MANUAL").orElseThrow();

        assertThat(run.status()).isEqualTo("OK");
        assertThat(run.issuesFound()).isZero();
    }

    @Test
    void corruptedBalance_isDetected() {
        UUID user = newUser(5_000);
        jdbc.update("update wallets set balance = balance + 500 where user_id = ?", user);
        try {
            RunSummary run = reconciliation.run("MANUAL").orElseThrow();

            assertThat(run.status()).isEqualTo("MISMATCH");
            assertThat(reconciliation.issues(run.id())).anySatisfy(issue -> {
                assertThat(issue.check()).isEqualTo("WALLET_BALANCE_MISMATCH");
                assertThat(issue.detail()).contains("balance=5500 ledger_sum=5000");
            });
        } finally {
            jdbc.update("update wallets set balance = balance - 500 where user_id = ?", user);
        }
    }
}