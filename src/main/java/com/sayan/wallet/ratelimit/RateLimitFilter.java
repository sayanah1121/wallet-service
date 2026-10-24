package com.sayan.wallet.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class RateLimitFilter extends OncePerRequestFilter {
    private static final int WINDOW_SECONDS = 60;

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = null;
        int limit = 0;

        if ("POST".equals(request.getMethod())) {
            String path = request.getRequestURI();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String ip = request.getRemoteAddr();

            if (path.equals("/api/v1/auth/login")) {
                key = "rl:login:" + ip;
                limit = 10;
            } else if (path.equals("/api/v1/auth/register")) {
                key = "rl:register:" + ip;
                limit = 5;
            } else if (path.equals("/api/v1/transfers") && auth != null) {
                key = "rl:transfer:" + auth.getName();
                limit = 15;
            } else if (path.equals("/api/v1/wallets/topup") && auth != null) {
                key = "rl:topup:" + auth.getName();
                limit = 15;
            }
        }

        if (key != null) {
            RateLimiter.Result result = limiter.check(key, limit, WINDOW_SECONDS);
            if (!result.allowed()) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
                response.setContentType("application/problem+json");
                response.getWriter().write("{\"status\":429,\"title\":\"Too Many Requests\","
                        + "\"detail\":\"Rate limit exceeded. Retry in " + result.retryAfterSeconds() + " seconds.\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}