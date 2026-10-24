CREATE TABLE reconciliation_runs (
    id                   UUID PRIMARY KEY,
    started_at           TIMESTAMPTZ NOT NULL,
    finished_at          TIMESTAMPTZ NOT NULL,
    status               VARCHAR(10) NOT NULL,
    trigger_type         VARCHAR(10) NOT NULL,
    wallets_checked      INT         NOT NULL,
    transactions_checked INT         NOT NULL,
    issues_found         INT         NOT NULL
);

CREATE TABLE reconciliation_issues (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id     UUID         NOT NULL REFERENCES reconciliation_runs(id) ON DELETE CASCADE,
    check_name VARCHAR(40)  NOT NULL,
    subject_id VARCHAR(64)  NOT NULL,
    detail     VARCHAR(255) NOT NULL
);

CREATE INDEX idx_recon_issues_run ON reconciliation_issues(run_id);