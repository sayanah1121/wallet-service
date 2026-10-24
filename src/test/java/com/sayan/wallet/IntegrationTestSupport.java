package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.sayan.wallet.auth.AuthDtos.LoginRequest;
import com.sayan.wallet.auth.AuthDtos.RegisterRequest;
import com.sayan.wallet.auth.AuthService;
import com.sayan.wallet.common.exception.ApiException;
import com.sayan.wallet.transaction.TransferDtos.TransferResponse;
import com.sayan.wallet.user.UserRepository;
import com.sayan.wallet.wallet.WalletService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Shared helpers for every integration test. Subclasses decide which beans to mock.
@SpringBootTest
@ActiveProfiles("test")
abstract class IntegrationTestSupport {
    static final String PASSWORD = "Passw0rd!23";
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired protected AuthService auth;
    @Autowired protected UserRepository users;
    @Autowired protected WalletService walletService;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Environment env;

    protected final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    protected record RaceResult(List<TransferResponse> succeeded, List<ApiException> rejected) {}

    protected record Reply(int status, String body, HttpHeaders headers) {
        JsonNode json() { return JSON.readTree(body); }
    }

    // ---- data setup ----

    protected UUID newUser(long fundedPaise) {
        String email = "u-" + UUID.randomUUID() + "@test.com";
        auth.register(new RegisterRequest(email, PASSWORD, "Test User"));
        UUID id = users.findByEmail(email).orElseThrow().getId();
        if (fundedPaise > 0) {
            walletService.topUp(id, fundedPaise);
        }
        return id;
    }

    protected String emailOf(UUID userId) {
        return users.findById(userId).orElseThrow().getEmail();
    }

    protected String tokenOf(UUID userId) {
        return auth.login(new LoginRequest(emailOf(userId), PASSWORD)).accessToken();
    }

    protected String adminToken() {
        UUID admin = newUser(0);
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin);
        // the role lives inside the JWT, so log in again to get a token that carries it
        return tokenOf(admin);
    }

    protected UUID walletIdOf(UUID userId) {
        return jdbc.queryForObject("select id from wallets where user_id = ?", UUID.class, userId);
    }

    // read straight from the DB so the Redis cache can never hide a wrong balance
    protected long balanceOf(UUID userId) {
        return jdbc.queryForObject("select balance from wallets where user_id = ?", Long.class, userId);
    }

    protected long ledgerSumOf(UUID userId) {
        return jdbc.queryForObject("""
                select coalesce(sum(case when l.direction = 'CREDIT' then l.amount else -l.amount end), 0)
                from ledger_entries l join wallets w on w.id = l.wallet_id
                where w.user_id = ?
                """, Long.class, userId);
    }

    protected int transferCountFrom(UUID userId) {
        return jdbc.queryForObject("""
                select count(*) from ledger_entries l
                join wallets w on w.id = l.wallet_id
                join transactions t on t.id = l.transaction_id
                where w.user_id = ? and t.type = 'TRANSFER' and l.direction = 'DEBIT'
                """, Integer.class, userId);
    }

    // ---- assertions ----

    protected void assertLedgerBalanced() {
        Long total = jdbc.queryForObject("""
                select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
                from ledger_entries
                """, Long.class);
        assertThat(total).isZero();
    }

    protected void assertWalletMatchesLedger(UUID... userIds) {
        for (UUID id : userIds) {
            assertThat(balanceOf(id)).as("wallet balance vs ledger for %s", id).isEqualTo(ledgerSumOf(id));
        }
    }

    protected ApiException expectApiError(Callable<?> call) {
        try {
            call.call();
        } catch (ApiException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("Unexpected exception: " + e, e);
        }
        throw new AssertionError("Expected an ApiException but the call succeeded");
    }

    protected static void await(Duration timeout, String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out after " + timeout + " waiting for: " + what);
    }

    // ---- concurrency ----

    // Starts all tasks at the same instant and collects successes and business rejections.
    protected RaceResult race(int threads, IntFunction<Callable<TransferResponse>> taskFactory)
            throws Exception {
        List<TransferResponse> succeeded = new ArrayList<>();
        List<ApiException> rejected = new ArrayList<>();
        for (Future<TransferResponse> f : startTogether(threads, taskFactory)) {
            try {
                succeeded.add(f.get(60, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof ApiException api) {
                    rejected.add(api);
                } else {
                    throw new AssertionError("Unexpected failure: " + e.getCause(), e.getCause());
                }
            }
        }
        return new RaceResult(succeeded, rejected);
    }

    // Fires all HTTP requests at the same instant and returns the replies in request order.
    protected List<Reply> fireTogether(int count, IntFunction<HttpRequest> requestFactory) throws Exception {
        List<Reply> replies = new ArrayList<>();
        for (Future<Reply> f : startTogether(count, i -> {
            HttpRequest request = requestFactory.apply(i);
            return () -> send(request);
        })) {
            replies.add(f.get(120, TimeUnit.SECONDS));
        }
        return replies;
    }

    private <T> List<Future<T>> startTogether(int threads, IntFunction<Callable<T>> taskFactory)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Callable<T> task = taskFactory.apply(i);
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        return futures;
    }

    // ---- HTTP (only for RANDOM_PORT subclasses) ----

    protected URI url(String path) {
        return URI.create("http://localhost:" + env.getRequiredProperty("local.server.port") + path);
    }

    protected HttpRequest.Builder transferRequest(String token, String idemKey, String toEmail, long amount) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url("/api/v1/transfers"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"toEmail\":\"%s\",\"amount\":%d}".formatted(toEmail, amount)));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (idemKey != null) {
            b.header("Idempotency-Key", idemKey);
        }
        return b;
    }

    protected HttpRequest.Builder jsonPost(String path, String token, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    protected HttpRequest.Builder get(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url(path)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    protected Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), r.headers());
    }

    protected Reply send(HttpRequest.Builder request) throws Exception {
        return send(request.build());
    }
}
