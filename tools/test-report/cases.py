"""Test-plan metadata for the report: one entry per automated test, keyed by Class#method."""

CATEGORIES = [
    (1, "Concurrency & race conditions (double-spend suite)"),
    (2, "Idempotency edge cases"),
    (3, "Distributed resilience & event-driven (Kafka, webhooks)"),
    (4, "Financial integrity & consistency"),
    (5, "Security & load limits"),
    (6, "Part 2 - Consumer idempotency & replay (Kafka failure modes)"),
    (7, "Part 2 - Isolation level, serialization & deadlock recovery"),
    (8, "Part 2 - Financial boundary & precision"),
]

CASES = {
    # ---------------- 1. concurrency ----------------
    "DoubleSpendSuiteTest#concurrentDrain_exactlyAffordableTransfersSucceed": dict(
        cat=1, id="TC-1.1a", title="Concurrent drain of a 100.00 balance",
        goal="Row locking (SELECT ... FOR UPDATE) must stop a balance being spent twice.",
        how="User A holds 100.00 (10,000 paise). 50 POST /api/v1/transfers requests of 10.00 each are "
            "released at the same instant over HTTP.",
        expected="Exactly 10 x 201 and 40 x 422 'Insufficient funds'. A ends at 0, B at 100.00, no 5xx, "
                 "wallet balances equal their ledger sums, ledger nets to zero."),
    "DoubleSpendSuiteTest#opposingDirections_noDeadlockNoNegativeBalance": dict(
        cat=1, id="TC-1.1b", title="Bidirectional A<->B transfers (deadlock test)",
        goal="Locking both wallets in a fixed (sorted id) order must prevent deadlocks.",
        how="A starts with 100.00, B with 0. 50 A->B and 50 B->A transfers of 10.00 are fired simultaneously "
            "(100 requests). B can only spend what it has received at that moment.",
        expected="Every reply is 201 or 422 (a deadlock would surface as 500). No negative balance. "
                 "A + B = 100.00 and each balance equals its ledger sum."),
    "DoubleSpendSuiteTest#ringOfThreeWallets_noDeadlock": dict(
        cat=1, id="TC-1.1c", title="Three-wallet ring A->B->C->A",
        goal="Lock ordering must also hold for cycles longer than two wallets.",
        how="Three wallets with 50.00 each; 60 simultaneous transfers of 1.00 around the ring.",
        expected="All 60 return 201, every wallet ends at 50.00, ledger balanced."),
    "DoubleSpendSuiteTest#idempotencyFlood_executesOnceAndReplaysSamePayload": dict(
        cat=1, id="TC-1.2", title="Rapid-fire idempotency flooding",
        goal="A retry storm with one Idempotency-Key must execute exactly one transfer.",
        how="100 identical POST /transfers requests with the same Idempotency-Key released at once; then a "
            "different transfer, then a late retry of the original key.",
        expected="All 100 return 201 with a byte-identical body; one transfer, one idempotency row, one event "
                 "published. The late retry returns the original payload even though the balance has moved."),
    "TransferConcurrencyTest#concurrentTransfers_neverOverdraw": dict(
        cat=1, id="TC-1.R1", title="Service-level overdraw race (regression)",
        goal="Same invariant as TC-1.1a at the service layer.",
        how="30 parallel TransferService calls of 10.00 against 100.00.",
        expected="10 succeed, 20 rejected with 422, sender at 0."),
    "TransferConcurrencyTest#opposingTransfers_doNotDeadlock": dict(
        cat=1, id="TC-1.R2", title="Service-level opposing transfers (regression)",
        goal="No deadlock for opposing transfers at the service layer.",
        how="20 parallel transfers, half A->B and half B->A.",
        expected="All 20 succeed; balances unchanged."),
    "TransferConcurrencyTest#sameIdempotencyKey_movesMoneyOnce": dict(
        cat=1, id="TC-1.R3", title="Service-level key flood (regression)",
        goal="Same invariant as TC-1.2 at the service layer.",
        how="10 parallel calls with one key.", expected="One transaction id, money moved once."),

    # ---------------- 2. idempotency ----------------
    "IdempotencyEdgeCaseTest#clientTimeoutAfterCommit_retryReturnsOriginalResult": dict(
        cat=2, id="TC-2.1a", title="Interrupted request: response lost after commit",
        goal="A client that never saw the response must be able to retry safely.",
        how="A separate DB connection locks the recipient wallet so the server stalls; the client gives up "
            "after 1 s (HttpTimeoutException). The lock is released, the abandoned request commits on the "
            "server, then the client retries with the same key.",
        expected="Retry returns 201 with the transaction id that was committed while the client was gone; "
                 "exactly one transfer; balances 90.00 / 10.00."),
    "IdempotencyEdgeCaseTest#retryWhileOriginalInFlight_getsSameResult": dict(
        cat=2, id="TC-2.1b", title="Retry while the original is still in flight",
        goal="A retry that overlaps the original must not execute a second transfer.",
        how="Original and retry are both sent while the recipient wallet is locked; both are verified to be "
            "pending, then the lock is released.",
        expected="Both return 201 with identical bodies; one transfer."),
    "IdempotencyEdgeCaseTest#failedRequestRollsBackKey_retryExecutes": dict(
        cat=2, id="TC-2.1c", title="Failure before commit does not burn the key",
        goal="A crash/abort mid-transaction must roll back the key claim together with the money movement.",
        how="Transfer with insufficient funds (key is claimed, then the transaction aborts). Top up, retry "
            "with the same key.",
        expected="First call 422 and no key row persisted; retry executes (201) exactly once."),
    "IdempotencyEdgeCaseTest#sameKeyDifferentAmount_isRejected": dict(
        cat=2, id="TC-2.2a", title="Payload mismatch with existing key (50.00 then 500.00)",
        goal="Reusing a key with different parameters must be rejected.",
        how="Idempotency-Key abc-123-... transfers 50.00, then the same key attempts 500.00.",
        expected="Test plan: 400. Implemented: 422 Unprocessable Content with detail "
                 "'Idempotency-Key was already used with a different request' (see finding 8). "
                 "Only 50.00 moves."),
    "IdempotencyEdgeCaseTest#sameKeyDifferentRecipient_isRejected": dict(
        cat=2, id="TC-2.2b", title="Same key, different recipient",
        goal="The request fingerprint must include the recipient.",
        how="Same key and amount, second request targets a different user.",
        expected="Second request 422; second recipient receives nothing."),
    "IdempotencyEdgeCaseTest#normalisedEmail_isTreatedAsSameRequest": dict(
        cat=2, id="TC-2.2c", title="Recipient e-mail case is normalised",
        goal="Cosmetic differences must not be treated as a different request.",
        how="Same key, recipient e-mail upper-cased on the retry.",
        expected="Retry is a replay (201, identical body); money moves once."),
    "IdempotencyEdgeCaseTest#keysAreScopedPerUser": dict(
        cat=2, id="TC-2.2d", title="Keys are scoped per user",
        goal="Two users choosing the same key must not collide.",
        how="Users A and C both send the same Idempotency-Key value.",
        expected="Both execute with different transaction ids."),
    "IdempotencyEdgeCaseTest#invalidKeyHeader_isRejected": dict(
        cat=2, id="TC-2.2e", title="Missing / blank / over-long key header",
        goal="Transfers without a usable key must be refused.",
        how="No header, a single space, and a 101-character key.",
        expected="400 for each; balance untouched."),
    "TransferConcurrencyTest#reusedKeyWithDifferentBody_isRejected": dict(
        cat=2, id="TC-2.R1", title="Key reuse with different body (regression)",
        goal="Service-level version of TC-2.2a.", how="Two calls, same key, amounts 1.00 and 2.00.",
        expected="Second call 422."),

    # ---------------- 3. events ----------------
    "EventPipelineTest#workersDown_transfersSucceed_eventsProcessedAfterRestart": dict(
        cat=3, id="TC-3.1a", title="Worker outage: transfers succeed, backlog processed on restart",
        goal="The payment path must not depend on the consumers, and no event may be lost while they are down.",
        how="The application's listener containers are stopped. Two private worker groups commit their "
            "offset at the current end of the topic (a worker that crashed just before the transfers). 5 "
            "transfers are made to a merchant with a registered webhook; a probe consumer verifies the 5 "
            "events are in the topic. The real NotificationConsumer and WebhookDeliveryConsumer beans are "
            "then restarted on those groups with the application's error handler. (Private groups keep the "
            "locally running app, which joins the real groups, from stealing partitions.)",
        expected="All transfers commit while workers are down; nothing is processed until restart; after "
                 "restart 10 notifications (sender + receiver) are stored and 5 signed webhooks delivered."),
    "EventPipelineTest#redeliveredEvent_isIdempotent": dict(
        cat=3, id="TC-3.1b", title="At-least-once redelivery does not duplicate notifications",
        goal="Kafka may redeliver; consumers must be idempotent.",
        how="The same TransferCompletedEvent is fed to the notification consumer three times.",
        expected="Exactly 2 notification rows (one per party)."),
    "EventPipelineTest#publisher_sendsOnlyAfterCommit": dict(
        cat=3, id="TC-3.1c", title="Events are published only after commit",
        goal="Consumers must never see an event for a rolled-back transfer.",
        how="TransferEventPublisher with a mocked KafkaTemplate inside a transaction that rolls back, then "
            "one that commits.",
        expected="No send on rollback; exactly one send on commit."),
    "EventPipelineTest#publisherFailure_isSwallowed": dict(
        cat=3, id="TC-3.1d", title="Publish failure never fails the transfer",
        goal="A broker error after commit must not surface as an API error.",
        how="KafkaTemplate.send throws inside afterCommit.", expected="No exception escapes the transaction."),
    "KafkaOutageTest#brokerDown_transferStillSucceeds": dict(
        cat=3, id="TC-3.1e", title="Kafka broker down: POST /transfers still returns 201",
        goal="Decoupled architecture: a broker outage must not block payments.",
        how="Application booted with bootstrap-servers pointing at a closed port and max.block.ms=3000. "
            "A transfer and an idempotent retry are sent over HTTP.",
        expected="201, balances and ledger correct, retry replays. Latency is bounded by max.block.ms "
                 "(see findings 6 and 7: the event itself is dropped)."),
    "KafkaOutageTest#brokerDown_concurrentTransfersAllCommit": dict(
        cat=3, id="TC-3.1f", title="Kafka broker down: concurrent transfers",
        goal="Measure the effect of the producer's metadata wait under concurrency.",
        how="20 users each send one transfer simultaneously while the broker is unreachable.",
        expected="All 20 commit with 201. Elapsed time shows the request threads (and DB connections) "
                 "held for max.block.ms each (see finding 7)."),
    "WebhookRetryTest#failsThreeTimesThenSucceeds_withExponentialBackoff": dict(
        cat=3, id="TC-3.2a", title="Merchant webhook fails 3x (HTTP 500), succeeds on attempt 4",
        goal="Unstable merchant servers must not lose webhooks; retries must back off exponentially.",
        how="Local merchant endpoint scripted to answer 500, 500, 500, 200. The real WebhookDeliveryConsumer "
            "runs in a listener container with the application's DefaultErrorHandler on a private topic.",
        expected="Exactly 4 attempts with gaps of ~1 s, ~2 s, ~4 s; each attempt carries the same body and a "
                 "valid HMAC-SHA256 signature; nothing in the DLT; no 5th attempt."),
    "WebhookRetryTest#timeoutThenSuccess": dict(
        cat=3, id="TC-3.2b", title="Merchant webhook times out once, then succeeds",
        goal="A hanging endpoint must be treated as a failure and retried.",
        how="First attempt hangs for 7 s (client timeout is 5 s), second answers 200.",
        expected="2 attempts, delivered, DLT empty."),
    "WebhookRetryTest#permanentFailure_goesToDeadLetterTopic": dict(
        cat=3, id="TC-3.2c", title="Merchant never recovers: event parked in DLT",
        goal="Retries must be bounded and failed events preserved.",
        how="Endpoint always answers 500.",
        expected="4 attempts, then exactly one record on <topic>.DLT (the dead-letter topic KafkaConfig "
                 "provisions) carrying the original payload and the exception message.",
        actual="FIXED (finding 1). Before the fix the record went to <topic>-dlt, the recoverer's default name, which "
               "the broker auto-created with 1 partition. KafkaConfig now passes an explicit destination resolver; "
               "the record lands on <topic>.DLT."),

    # ---------------- 4. integrity ----------------
    "LedgerIntegrityTest#chaosThenReconciliation_reportsNoDiscrepancies": dict(
        cat=4, id="TC-4.1", title="Invariant balance check after a chaotic soak",
        goal="Prove the double-entry ledger never drifts from wallet balances.",
        how="16 workers x 20 users run a random mix for the soak duration: top-ups, transfers (many "
            "overdrawing), self-transfers, unknown recipients, idempotent replays and key reuse with a "
            "different body. The reconciliation job is then triggered through POST "
            "/api/v1/admin/reconciliation/runs with an ADMIN token.",
        expected="No unexpected errors; every balance equals an independently computed model and its "
                 "SUM(credits) - SUM(debits); no running balance ever negative; all wallets sum to zero; "
                 "reconciliation status OK with 0 issues."),
    "LedgerIntegrityTest#reconciliation_detectsInjectedCorruption": dict(
        cat=4, id="TC-4.2", title="Reconciliation detects corruption",
        goal="The checker must actually catch drift (no false negatives).",
        how="Insert a COMPLETED transaction without ledger entries and an extra 0.01 credit on a real "
            "transaction, run reconciliation, read the issues via the admin API, then clean up.",
        expected="Status MISMATCH with TRANSACTION_WITHOUT_LEDGER, UNBALANCED_TRANSACTION, "
                 "WALLET_BALANCE_MISMATCH and LEDGER_NOT_ZERO_SUM."),
    "LedgerIntegrityTest#concurrentRun_isRefused": dict(
        cat=4, id="TC-4.3", title="Only one reconciliation run at a time",
        goal="The Redis lock must stop overlapping runs across instances.",
        how="Hold lock:reconciliation in Redis, call the admin API, release, call again.",
        expected="409 while the lock is held, 200 afterwards."),
    "LedgerIntegrityTest#invalidAmounts_neverReachTheLedger": dict(
        cat=4, id="TC-4.4", title="Invalid amounts never touch the ledger",
        goal="Validation must stop zero, negative and oversized amounts before any write.",
        how="Transfers of 0, -5.00 and 100,000.01; top-up of -0.01.",
        expected="400 for each; ledger row count and balance unchanged."),
    "ReconciliationTest#cleanLedger_reportsOk": dict(
        cat=4, id="TC-4.R1", title="Clean ledger reports OK (regression)",
        goal="Baseline reconciliation.", how="One transfer, run reconciliation.", expected="OK, 0 issues."),
    "ReconciliationTest#corruptedBalance_isDetected": dict(
        cat=4, id="TC-4.R2", title="Corrupted balance detected (regression)",
        goal="Detect a wallet balance edited outside the ledger.", how="Add 5.00 to a balance directly.",
        expected="MISMATCH with WALLET_BALANCE_MISMATCH."),
    "TransferConcurrencyTest#insufficientFunds_isRejectedAndLeavesBalancesUntouched": dict(
        cat=4, id="TC-4.R3", title="Insufficient funds leaves balances untouched (regression)",
        goal="Rejected transfer writes nothing.", how="Transfer 10.00 from a 5.00 wallet.",
        expected="422, balances unchanged."),
    "TransferConcurrencyTest#transferToSelf_isRejected": dict(
        cat=4, id="TC-4.R4", title="Self-transfer rejected (regression)",
        goal="No-op transfers are refused.", how="Transfer to own e-mail.", expected="400."),

    # ---------------- 5. security ----------------
    "SecurityBoundaryTest#expiredToken_isUnauthorized": dict(
        cat=5, id="TC-5.1a", title="Expired JWT",
        goal="Expired tokens must be refused.", how="Token correctly signed but expired an hour ago.",
        expected="401; victim balance untouched."),
    "SecurityBoundaryTest#tamperedSignature_isUnauthorized": dict(
        cat=5, id="TC-5.1b", title="Signature-tampered JWT",
        goal="Any change to the signature must invalidate the token.", how="One signature character flipped.",
        expected="401."),
    "SecurityBoundaryTest#payloadSwappedToVictim_isUnauthorized": dict(
        cat=5, id="TC-5.1c", title="Payload swapped to another user",
        goal="An attacker must not impersonate a victim by editing the subject.",
        how="Attacker's token with the payload replaced by the victim's id, original signature kept.",
        expected="401; victim not debited."),
    "SecurityBoundaryTest#foreignKey_isUnauthorized": dict(
        cat=5, id="TC-5.1d", title="JWT signed with a foreign key",
        goal="Only tokens signed with the server secret are accepted.", how="Token signed with another HMAC key.",
        expected="401."),
    "SecurityBoundaryTest#algNone_isUnauthorized": dict(
        cat=5, id="TC-5.1e", title="Unsigned 'alg: none' JWT",
        goal="Classic alg=none bypass must fail.", how="Header {alg: none}, empty signature.", expected="401."),
    "SecurityBoundaryTest#malformedOrMissing_isUnauthorized": dict(
        cat=5, id="TC-5.1f", title="Malformed, missing or wrong-scheme token",
        goal="Anything other than a valid Bearer token is unauthenticated.",
        how="'not.a.jwt', no Authorization header, 'Basic <jwt>'.", expected="401 each."),
    "SecurityBoundaryTest#roleEscalation_isUnauthorized": dict(
        cat=5, id="TC-5.1g", title="Role claim escalated to ADMIN",
        goal="Users cannot grant themselves admin rights.",
        how="Payload edited to role=ADMIN with the original signature; also the genuine USER token.",
        expected="401 for the forged token, 403 for the genuine USER token on the admin API."),
    "SecurityBoundaryTest#cannotDebitAnotherUsersWallet": dict(
        cat=5, id="TC-5.1h", title="User A attempts to debit user B's wallet",
        goal="The debited wallet must come only from the authenticated principal.",
        how="A's token with body fields fromUserId / fromWalletId / senderId set to B.",
        expected="The API has no way to name a source wallet: the spoofed fields are ignored, A is debited, "
                 "B is untouched. B's token only ever sees B's wallet."),
    "SecurityHttpTest#noToken_isUnauthorized": dict(
        cat=5, id="TC-5.R1", title="No token -> 401 (regression)", goal="Baseline.", how="GET /users/me.",
        expected="401."),
    "SecurityHttpTest#userRole_isForbiddenOnAdminEndpoints": dict(
        cat=5, id="TC-5.R2", title="USER role on admin API -> 403 (regression)", goal="Baseline.",
        how="POST admin runs with USER token.", expected="403."),
    "SecurityHttpTest#refreshToken_worksOnlyOnce": dict(
        cat=5, id="TC-5.R3", title="Refresh token rotation (regression)", goal="Refresh tokens are single use.",
        how="Refresh twice with the same token.", expected="Second refresh rejected."),
    "RateLimitTest#loginBurst_isThrottled": dict(
        cat=5, id="TC-5.2a", title="Login flood: 500 simultaneous requests from one IP",
        goal="Brute force on /auth/login must be throttled.",
        how="500 POST /api/v1/auth/login requests released at once from 127.0.0.1 (limit 10/min/IP).",
        expected="Exactly 10 reach authentication (401), 490 get 429 with Retry-After 1-60 s and a "
                 "problem+json body; the Redis counter has a TTL."),
    "RateLimitTest#transferBurst_isThrottledPerUser": dict(
        cat=5, id="TC-5.2b", title="Transfer flood from one user",
        goal="Per-user transfer limit (15/min) must hold under concurrency without penalising others.",
        how="50 simultaneous transfers from one user, then one transfer from another user.",
        expected="Exactly 15 x 201 and 35 x 429; only 15 transfers move money; the other user gets 201."),
    "RateLimitTest#registerBurst_isThrottled": dict(
        cat=5, id="TC-5.2c", title="Registration limit", goal="Account-creation spam is throttled.",
        how="8 registrations from one IP (limit 5/min).", expected="5 x 201, 3 x 429."),
    "RateLimitTest#readsAreNotThrottled": dict(
        cat=5, id="TC-5.2d", title="Reads are not throttled", goal="Limits apply only to write endpoints.",
        how="40 simultaneous GET /wallets/me.", expected="All 200."),
    "RateLimitTest#redisDown_failsOpen": dict(
        cat=5, id="TC-5.2e", title="Limiter behaviour when Redis is down",
        goal="Document the failure mode of the limiter.", how="RateLimiter with a Redis client that throws.",
        expected="Requests are allowed (fail-open). See finding 9."),

    # ---------------- part 2 ----------------
    "ConsumerReplayTest#webhookWorkerCrash_merchantReceivesEventOnce": dict(
        cat=6, id="P2-TC-1.1a", title="Webhook worker crashes after firing, before committing the offset",
        goal="A redelivered TransferCompleted event must not make the merchant receive the same webhook twice.",
        how="Real WebhookDeliveryConsumer on a private topic in a MANUAL-ack container: it delivers the webhook "
            "and is stopped without acknowledging (the offset state a killed worker leaves; verified: no "
            "committed offset). A new worker on the same group receives the redelivered record.",
        expected="Merchant receives the webhook exactly once (the service tracks delivered event ids).",
        actual="FIXED (finding 2). Before the fix the merchant received the event twice. Successful deliveries are now "
               "recorded in webhook_deliveries (V7 migration) per (transaction, endpoint) and skipped on "
               "redelivery; each webhook also carries an X-Wallet-Event-Id header."),
    "ConsumerReplayTest#notificationWorkerCrash_noDuplicateNotifications": dict(
        cat=6, id="P2-TC-1.1b", title="Notification worker crashes before committing the offset",
        goal="Redelivery must not create duplicate notifications.",
        how="Same crash simulation with the real NotificationConsumer after a real transfer.",
        expected="The event is processed twice, yet exactly 2 notification rows exist (existence check plus "
                 "UNIQUE(transaction_id, user_id))."),
    "ContentionRecoveryTest#hotWalletWithInjectedDelay_allSucceed": dict(
        cat=7, id="P2-TC-2.1a", title="Hot wallet with an artificial delay inside the transaction",
        goal="Pessimistic locking must stay correct and error-free under heavy contention on one row.",
        how="A test-only PostgreSQL trigger adds pg_sleep(20 ms) to every ledger insert, holding row locks "
            "about 40 ms per transfer. 20 users pay a hot wallet while it pays each of them back: 40 "
            "simultaneous transfers through the API.",
        expected="All 40 return 201, no 5xx, balances exact, ledger balanced. Isolation is READ COMMITTED with "
                 "SELECT ... FOR UPDATE, so 40001 serialization failures cannot occur on this path."),
    "ContentionRecoveryTest#forcedDeadlock_isRetriedNot500": dict(
        cat=7, id="P2-TC-2.1b", title="Real PostgreSQL deadlock (40P01) during a transfer",
        goal="A deadlock or serialization failure must be retried with backoff, not returned as an error.",
        how="A second DB connection locks the wallet the app locks second, waits until the app holds its first "
            "lock, then requests that one too. PostgreSQL detects the cycle after deadlock_timeout (1 s) and "
            "aborts the application's transaction (verified). The external locks are then released, so a "
            "retry could succeed.",
        expected="201 after an internal retry; money moved once.",
        actual="FIXED (findings 3 and 4). Before the fix the 40P01 escaped and the client got an empty 401. "
               "TransferController.transfer is now @Retryable on ConcurrencyFailureException (3 retries, 50 ms "
               "exponential backoff with jitter), so the retry succeeds once the lock is released."),
    "ErrorResponseTest#unhandledError_is500": dict(
        cat=7, id="P2-TC-2.1c", title="Unhandled server error seen by a logged-in client",
        goal="A server fault must be reported as a server fault, never as an authentication failure.",
        how="TransferService mocked to throw IllegalStateException; POST /transfers with a valid token.",
        expected="500 (before the fix: empty 401, because /error was not permitted). Regression test for finding 4."),
    "ErrorResponseTest#transientConflict_isRetried": dict(
        cat=7, id="P2-TC-2.1d", title="Transient lock conflict is retried",
        goal="Deadlock/serialization losers are retried transparently.",
        how="TransferService mocked to throw CannotAcquireLockException twice, then succeed.",
        expected="201 after exactly 3 attempts."),
    "ErrorResponseTest#persistentConflict_is503": dict(
        cat=7, id="P2-TC-2.1e", title="Conflict that outlasts the retries",
        goal="When retries are exhausted the client gets an actionable, retryable error.",
        how="TransferService mocked to always throw CannotAcquireLockException.",
        expected="4 attempts (1 + 3 retries), then 503 problem+json with Retry-After: 1."),
    "PrecisionBoundaryTest#thirtyThreeThirtyThreeThreeTimes": dict(
        cat=8, id="P2-TC-3.1a", title="33.33 transferred three times",
        goal="No fractional paise may be lost or created.",
        how="99.99 sent as three transfers of 33.33 to three users.",
        expected="Sender exactly 0.00, each receiver 33.33, wallets equal ledger sums."),
    "PrecisionBoundaryTest#splitsAndMicroTransfersStayExact": dict(
        cat=8, id="P2-TC-3.1b", title="Three-way split and 200 one-paisa transfers",
        goal="Uneven splits and the smallest unit stay exact under concurrency.",
        how="100.00 split as 33.33 + 33.33 + 33.34; then 200 simultaneous transfers of 0.01; reconciliation run.",
        expected="Totals exact to the paisa; reconciliation OK."),
    "PrecisionBoundaryTest#fractionalAmounts_areRejectedNotTruncated": dict(
        cat=8, id="P2-TC-3.1c", title="Fractional amounts in the JSON body",
        goal="A client sending rupees as a decimal (e.g. 33.33) must get an error, not a different amount.",
        how="POST /transfers with amount 33.33, 0.5, 1e-2 and 3333.999 (amount is an integer number of paise).",
        expected="400 for every fractional value; nothing moves.",
        actual="FIXED (finding 5). Before the fix 33.33 and 3333.999 were accepted and truncated to 33 and 3333 paise. "
               "spring.jackson.deserialization.accept-float-as-int=false now rejects every fractional amount with 400."),
    "PrecisionBoundaryTest#amountBoundaries": dict(
        cat=8, id="P2-TC-3.1d", title="Amount boundaries",
        goal="Edges of the accepted range behave exactly.",
        how="0.01, the 100,000.00 maximum, maximum + 0.01, and a number beyond Long.MAX_VALUE.",
        expected="201, 201, 400, 400 (no overflow, no 500)."),
    "PrecisionBoundaryTest#noFloatingPointInMoneyModel": dict(
        cat=8, id="P2-TC-3.1e", title="No floating point in the money model",
        goal="Money must be held as scaled integers or BigDecimal, never float/double.",
        how="Reflection over every compiled application class; information_schema check of all money columns.",
        expected="Zero float/double fields; wallets.balance, transactions.amount, ledger_entries.amount/"
                 "balance_after and idempotency_keys.balance_after are all BIGINT (paise). This satisfies the "
                 "'scaled integer' option in the plan, so BigDecimal is not needed."),
}

FINDINGS = [
    dict(severity="High", fixed=True, title="Dead letters go to an unprovisioned '-dlt' topic (TC-3.2c failed)",
         detail="KafkaConfig creates transfer-completed.DLT with 3 partitions and its comment says failed records "
                "go there, but DeadLetterPublishingRecoverer in Spring Kafka 4 publishes to "
                "<topic>-dlt. The broker auto-creates transfer-completed-dlt with 1 partition; the recoverer "
                "warns that the partition does not exist and lets the producer pick one. Anything that monitors "
                "or replays .DLT never sees a failed webhook; with auto-topic-creation disabled (common in "
                "production) the records would be lost outright.",
         fix="KafkaConfig builds DeadLetterPublishingRecoverer with an explicit resolver (rec.topic() + '.DLT', same "
             "partition). The stray transfer-completed-dlt topic on the dev broker can be deleted."),
    dict(severity="High", fixed=True, title="Redelivered events send duplicate webhooks (P2-TC-1.1a failed)",
         detail="WebhookDeliveryConsumer keeps no record of delivered events. A worker that crashes after "
                "posting but before committing its offset, a consumer rebalance, or any retry re-sends the "
                "webhook: the test merchant received the same event twice. Notifications are safe (P2-TC-1.1b).",
         fix="V7 migration adds webhook_deliveries (transaction_id, endpoint_id) PK; WebhookDeliveryConsumer skips "
             "pairs already delivered and records each success. Webhooks now carry X-Wallet-Event-Id so merchants "
             "can dedupe the remaining crash window (between POST and insert)."),
    dict(severity="High", fixed=True, title="Deadlocks and serialization failures are not retried (P2-TC-2.1b failed)",
         detail="A real 40P01 raised during TransferService.transfer propagates as CannotAcquireLockException; "
                "there is no retry. The sorted lock order prevents deadlocks between transfers, but any other "
                "writer to wallets (admin scripts, future features, lock timeouts) will fail user requests.",
         fix="TransferController.transfer is @Retryable(includes = ConcurrencyFailureException, maxRetries = 3, "
             "delay = 50 ms, multiplier = 2, jitter = 20 ms), outside the transaction, enabled by ResilienceConfig "
             "(@EnableResilientMethods, Spring 7). Exhausted retries return 503 with Retry-After (P2-TC-2.1e)."),
    dict(severity="Medium", fixed=True, title="Unhandled server errors reach the client as 401 with an empty body",
         detail="Seen in P2-TC-2.1b: when a controller throws, Tomcat forwards to /error, which falls under "
                "anyRequest().authenticated(). The stateless error dispatch has no authentication, so a "
                "logged-in user gets 401. Clients will treat a database fault as an expired session and log out.",
         fix="/error is permitAll() in SecurityConfig; GlobalExceptionHandler maps ConcurrencyFailureException to "
             "503 problem+json. Regression test P2-TC-2.1c."),
    dict(severity="High", fixed=True, title="Fractional amounts are silently truncated (P2-TC-3.1c failed)",
         detail="amount is a long in paise, and Jackson coerces JSON floats to integers. {\"amount\": 33.33} "
                "transfers 0.33 rupees (33 paise) and returns 201; 3333.999 becomes 3333. A client that "
                "sends rupees as a decimal moves the wrong amount with no error.",
         fix="application.yml sets spring.jackson.deserialization.accept-float-as-int: false; every fractional "
             "amount now gets 400."),
    dict(severity="High", title="Events produced during a Kafka broker outage are lost",
         detail="TransferEventPublisher sends after commit and only logs a failure. With the broker unreachable "
                "(TC-3.1e) the transfer commits but its event is dropped, so notifications and webhooks never "
                "happen. Test-plan 3.1 ('events aren't lost') holds for worker outages (TC-3.1a) but not for "
                "broker outages at publish time.",
         fix="Write the event to an outbox table in the same DB transaction as the transfer and publish it "
             "from a relay (polling or Debezium), marking rows sent on ack."),
    dict(severity="High", title="Broker outage stalls every transfer response and holds a DB connection",
         detail="afterCommit runs on the request thread before the JDBC connection is released, and "
                "KafkaProducer.send blocks for max.block.ms waiting for metadata (default 60 s). TC-3.1e/f "
                "measured this with max.block.ms=3 s. With defaults, each transfer during an outage takes ~60 s "
                "and pins one of the 10 Hikari connections, so the whole API (reads too) degrades within seconds.",
         fix="The outbox in finding 6 removes Kafka from the request path. As a stop-gap set "
             "spring.kafka.producer.properties.max.block.ms to ~1000 and hand the send to an executor."),
    dict(severity="Low", title="Key reuse with a different payload returns 422, not 400",
         detail="TC-2.2a: the plan expects 400; the API returns 422 Unprocessable Content. 422 is what the IETF "
                "'Idempotency-Key HTTP Header' draft specifies for this case, so the behaviour is defensible.",
         fix="Keep 422 and update the test plan / API documentation."),
    dict(severity="Medium", title="Rate limiter fails open when Redis is unavailable",
         detail="TC-5.2e: any Redis error allows the request. During a Redis outage, /auth/login loses its "
                "brute-force protection entirely.",
         fix="Fail closed for /auth/login (or fall back to an in-memory per-instance bucket such as Bucket4j)."),
    dict(severity="Low", title="Login limit keys on the socket address",
         detail="Code review: RateLimitFilter uses request.getRemoteAddr(). Behind a load balancer or proxy every "
                "client shares one address, so 10 failed logins anywhere lock everyone out for a minute.",
         fix="Enable server.forward-headers-strategy=native (or framework) behind a trusted proxy."),
    dict(severity="Low", title="Dead-letter topic has no consumer",
         detail="Now that dead letters reach .DLT (finding 1), still nothing reads or replays the dead-letter topic. The per-endpoint delivery log added for finding 2 "
                "also stops retries from re-sending to endpoints that already succeeded.",
         fix="Add an admin endpoint or job that replays .DLT records once the merchant endpoint is healthy, "
             "and alert on DLT growth."),
    dict(severity="Info", title="Access tokens are trusted until expiry",
         detail="Code review: JwtAuthFilter does not consult the database, so a disabled user or demoted admin "
                "keeps access for up to 15 minutes. Not exercised by a test.",
         fix="Acceptable for a 15-minute TTL; if instant revocation is needed, add a token version on the user."),
    dict(severity="Info", title="Soak duration scaled down",
         detail="TC-4.1 runs for 20 s by default instead of the 1 hour in the plan, to keep the suite fast. The "
                "invariants checked are identical.",
         fix="Run -Dchaos.seconds=3600 -Dtest=LedgerIntegrityTest nightly in CI."),
]
