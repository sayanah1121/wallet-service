package com.sayan.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sayan.wallet.ratelimit.RateLimiter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

// Category 5.2: Redis-backed fixed-window rate limits.
// login 10/min per IP, register 5/min per IP, transfers 15/min per user.
class RateLimitTest extends HttpTestBase {
    private static final List<String> LOOPBACK = List.of("127.0.0.1", "0:0:0:0:0:0:0:1");

    @Autowired StringRedisTemplate redis;

    @BeforeEach
    @AfterEach
    void resetIpCounters() {
        for (String ip : LOOPBACK) {
            redis.delete(List.of("rl:login:" + ip, "rl:register:" + ip));
        }
    }

    private static long count(List<Reply> replies, int status) {
        return replies.stream().filter(r -> r.status() == status).count();
    }

    @Test
    @DisplayName("TC-5.2a: 500 simultaneous logins from one IP -> 10 processed, 490 get 429")
    void loginBurst_isThrottled() throws Exception {
        String body = "{\"email\":\"nobody-%s@test.com\",\"password\":\"wrong-password\"}".formatted(UUID.randomUUID());

        // 5 back-to-back waves of 100 simultaneous connections: a single wave of 500 overflows Tomcat's
        // accept backlog (100) on Windows and turns into client-side connection errors
        long start = System.nanoTime();
        List<Reply> replies = new java.util.ArrayList<>();
        for (int wave = 0; wave < 5; wave++) {
            replies.addAll(fireTogether(100, i -> jsonPost("/api/v1/auth/login", null, body).build()));
        }
        long elapsedMs = Math.max(1, (System.nanoTime() - start) / 1_000_000);

        assertThat(count(replies, 401)).isEqualTo(10);
        assertThat(count(replies, 429)).isEqualTo(490);
        assertThat(replies).filteredOn(r -> r.status() == 429).allSatisfy(r -> {
            long retryAfter = Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow());
            assertThat(retryAfter).isBetween(1L, 60L);
            assertThat(r.headers().firstValue("Content-Type").orElseThrow()).contains("application/problem+json");
            assertThat(r.body()).contains("\"status\":429");
        });
        String ipKey = LOOPBACK.stream().map(ip -> "rl:login:" + ip).filter(redis::hasKey).findFirst().orElseThrow();
        assertThat(redis.getExpire(ipKey)).isBetween(1L, 60L);

        TestMetrics.record("TC-5.2a", "requests", 500);
        TestMetrics.record("TC-5.2a", "allowed", count(replies, 401));
        TestMetrics.record("TC-5.2a", "throttled_429", count(replies, 429));
        TestMetrics.record("TC-5.2a", "elapsed_ms", elapsedMs);
        TestMetrics.record("TC-5.2a", "requests_per_second", 500_000 / elapsedMs);
    }

    @Test
    @DisplayName("TC-5.2b: 50 simultaneous transfers from one user -> 15 processed, 35 get 429; other users unaffected")
    void transferBurst_isThrottledPerUser() throws Exception {
        UUID a = newUser(100_000);
        UUID b = newUser(0);
        UUID bystander = newUser(10_000);
        String token = tokenOf(a);
        String toB = emailOf(b);

        List<Reply> replies = fireTogether(50, i ->
                transferRequest(token, "burst-" + UUID.randomUUID(), toB, 1_000).build());

        assertThat(count(replies, 201)).isEqualTo(15);
        assertThat(count(replies, 429)).isEqualTo(35);
        // only the requests that got through moved money
        assertThat(balanceOf(a)).isEqualTo(100_000 - 15 * 1_000);
        assertThat(balanceOf(b)).isEqualTo(15 * 1_000);

        assertThat(send(transferRequest(tokenOf(bystander), "free-" + UUID.randomUUID(), toB, 1_000)).status())
                .isEqualTo(201);
        assertLedgerBalanced();
        TestMetrics.record("TC-5.2b", "requests", 50);
        TestMetrics.record("TC-5.2b", "allowed_201", count(replies, 201));
        TestMetrics.record("TC-5.2b", "throttled_429", count(replies, 429));
    }

    @Test
    @DisplayName("TC-5.2c: registration limited to 5 per minute per IP")
    void registerBurst_isThrottled() throws Exception {
        int created = 0;
        int throttled = 0;
        for (int i = 0; i < 8; i++) {
            String body = "{\"email\":\"rl-%s@test.com\",\"password\":\"%s\",\"fullName\":\"Rate Limit\"}"
                    .formatted(UUID.randomUUID(), PASSWORD);
            int status = send(jsonPost("/api/v1/auth/register", null, body)).status();
            if (status == 201) created++;
            if (status == 429) throttled++;
        }
        assertThat(created).isEqualTo(5);
        assertThat(throttled).isEqualTo(3);
    }

    @Test
    @DisplayName("TC-5.2d: read-only endpoints are not throttled")
    void readsAreNotThrottled() throws Exception {
        String token = tokenOf(newUser(1_000));
        List<Reply> replies = fireTogether(40, i -> get("/api/v1/wallets/me", token).build());
        assertThat(replies).allMatch(r -> r.status() == 200);
    }

    @Test
    @DisplayName("TC-5.2e: limiter fails open when Redis is unavailable")
    @SuppressWarnings("unchecked")
    void redisDown_failsOpen() {
        StringRedisTemplate broken = mock(StringRedisTemplate.class);
        when(broken.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        RateLimiter limiter = new RateLimiter(broken);

        assertThat(limiter.check("rl:test", 1, 60).allowed()).isTrue();
        assertThat(limiter.check("rl:test", 1, 60).allowed()).isTrue();
    }
}
