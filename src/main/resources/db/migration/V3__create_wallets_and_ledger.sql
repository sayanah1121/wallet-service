CREATE TABLE wallets (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID UNIQUE REFERENCES users(id),
    type       VARCHAR(10)  NOT NULL DEFAULT 'USER',
    currency   VARCHAR(3)   NOT NULL DEFAULT 'INR',
    balance    BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT wallets_balance_non_negative CHECK (type = 'SYSTEM' OR balance >= 0)
);

CREATE TABLE transactions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type        VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    amount      BIGINT       NOT NULL CHECK (amount > 0),
    currency    VARCHAR(3)   NOT NULL DEFAULT 'INR',
    description VARCHAR(255),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID        NOT NULL REFERENCES transactions(id),
    wallet_id      UUID        NOT NULL REFERENCES wallets(id),
    direction      VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount         BIGINT      NOT NULL CHECK (amount > 0),
    balance_after  BIGINT      NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_wallet_created ON ledger_entries(wallet_id, created_at DESC);
CREATE INDEX idx_ledger_transaction ON ledger_entries(transaction_id);

-- the system wallet that funds top-ups (balance may go negative)
INSERT INTO wallets (id, user_id, type)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM');

-- give existing users a wallet
INSERT INTO wallets (user_id) SELECT id FROM users;