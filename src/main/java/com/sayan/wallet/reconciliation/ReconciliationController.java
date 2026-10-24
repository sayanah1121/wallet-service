package com.sayan.wallet.reconciliation;

import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.reconciliation.ReconciliationService.Issue;
import com.sayan.wallet.reconciliation.ReconciliationService.RunSummary;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reconciliation")
@RequiredArgsConstructor
public class ReconciliationController {
    private final ReconciliationService service;

    @PostMapping("/runs")
    public RunSummary runNow() {
        return service.run("MANUAL").orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "A reconciliation run is already in progress"));
    }

    @GetMapping("/runs")
    public List<RunSummary> recentRuns() {
        return service.recentRuns();
    }

    @GetMapping("/runs/{id}/issues")
    public List<Issue> issues(@PathVariable UUID id) {
        return service.issues(id);
    }
}