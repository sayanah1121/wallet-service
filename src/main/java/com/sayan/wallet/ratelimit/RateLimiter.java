package com.sayan.wallet.ratelimit;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimiter {

    // INCR + EXPIRE in one atomic step, so a counter can never be left without a TTL
    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;

    public record Result(boolean allowed, long retryAfterSeconds) {}

    public Result check(String key, int limit, int windowSeconds) {
        try {
            Long count = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(windowSeconds));
            if (count != null && count > limit) {
                Long ttl = redis.getExpire(key);
                return new Result(false, (ttl == null || ttl < 1) ? windowSeconds : ttl);
            }
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
        }
        return new Result(true, 0);
    }
}