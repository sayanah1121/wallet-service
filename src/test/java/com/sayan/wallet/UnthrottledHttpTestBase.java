package com.sayan.wallet;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sayan.wallet.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// For load-style tests: the per-user limit (15/min) would otherwise turn most requests into 429s.
// The limiter itself is covered by RateLimitTest.
abstract class UnthrottledHttpTestBase extends HttpTestBase {

    @MockitoBean
    protected RateLimiter rateLimiter;

    @BeforeEach
    void allowEverything() {
        when(rateLimiter.check(anyString(), anyInt(), anyInt())).thenReturn(new RateLimiter.Result(true, 0));
    }
}
