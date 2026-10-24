-- one row per (event, endpoint) that was delivered successfully, so a redelivered
-- Kafka record (crash before offset commit, rebalance, retry) is not posted twice
CREATE TABLE webhook_deliveries (
    transaction_id UUID        NOT NULL,
    endpoint_id    UUID        NOT NULL REFERENCES webhook_endpoints(id),
    delivered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (transaction_id, endpoint_id)
);
