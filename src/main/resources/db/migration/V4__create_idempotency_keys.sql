CREATE TABLE idempotency_keys (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES users(id),
    idem_key       VARCHAR(100) NOT NULL,
    request_hash   VARCHAR(64)  NOT NULL,
    transaction_id UUID REFERENCES transactions(id),
    balance_after  BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key)
);