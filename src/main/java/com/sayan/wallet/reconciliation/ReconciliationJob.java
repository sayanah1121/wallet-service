package com.sayan.wallet.reconciliation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationJob {
    private final ReconciliationService service;

    // default: every day at 02:00 India time; override with app.reconciliation.cron
    @Scheduled(cron = "${app.reconciliation.cron:0 0 2 * * *}", zone = "Asia/Kolkata")
    public void nightly() {
        service.run("SCHEDULED").ifPresentOrElse(
                r -> log.info("Reconciliation {} finished: {} ({} issues)",
                        r.id(), r.status(), r.issuesFound()),
                () -> log.info("Reconciliation skipped: another instance holds the lock"));
    }
}