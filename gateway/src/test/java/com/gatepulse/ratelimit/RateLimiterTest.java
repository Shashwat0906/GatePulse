package com.gatepulse.ratelimit;

import com.gatepulse.core.FilterChain;
import com.gatepulse.core.GatewayResponse;
import com.gatepulse.core.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private final AtomicLong clock = new AtomicLong();

    private RateLimitConfig tokenBucket(int limit) {
        return new RateLimitConfig(true, RateLimitAlgorithm.TOKEN_BUCKET, limit, 1, 1000);
    }

    @Test
    void disabledLimiterAllowsEverything() {
        RateLimiter limiter = new RateLimiter(tokenBucket(1).withEnabled(false), clock::get);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        }
        assertThat(limiter.tryAcquire("c").isUnlimited()).isTrue();
    }

    @Test
    void switchingAlgorithmAtRuntimeTakesEffectImmediately() {
        RateLimiter limiter = new RateLimiter(tokenBucket(2), clock::get);
        limiter.tryAcquire("c");
        limiter.tryAcquire("c");
        assertThat(limiter.tryAcquire("c").allowed()).isFalse();

        limiter.update(limiter.config().withAlgorithm(RateLimitAlgorithm.SLIDING_WINDOW_LOG).withLimit(5));

        assertThat(limiter.config().algorithm()).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW_LOG);
        assertThat(limiter.tryAcquire("c").remaining()).as("fresh allowance under new rules").isEqualTo(4);
    }

    @Test
    void togglingEnabledKeepsPerClientState() {
        RateLimiter limiter = new RateLimiter(tokenBucket(2), clock::get);
        limiter.tryAcquire("c");
        limiter.tryAcquire("c");

        limiter.update(limiter.config().withEnabled(false));
        limiter.update(limiter.config().withEnabled(true));

        assertThat(limiter.tryAcquire("c").allowed()).as("bucket was not reset by toggling").isFalse();
    }

    @Test
    void configValidation() {
        assertThatThrownBy(() -> tokenBucket(0)).hasMessageContaining("limit");
        assertThatThrownBy(() -> new RateLimitConfig(true, RateLimitAlgorithm.TOKEN_BUCKET, 10, 0, 1000))
                .hasMessageContaining("refillPerSecond");
        assertThatThrownBy(() -> new RateLimitConfig(true, RateLimitAlgorithm.SLIDING_WINDOW_LOG, 10, 1, 0))
                .hasMessageContaining("windowMs");
        assertThat(RateLimitAlgorithm.fromId("sliding_window")).isEqualTo(RateLimitAlgorithm.SLIDING_WINDOW_LOG);
        assertThat(RateLimitAlgorithm.fromId("TOKEN-BUCKET")).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThatThrownBy(() -> RateLimitAlgorithm.fromId("leaky")).hasMessageContaining("token-bucket");
    }

    @Test
    void filterAddsHeadersAndRejectsWith429() throws Exception {
        RateLimiter limiter = new RateLimiter(tokenBucket(2), clock::get);
        RateLimitFilter filter = new RateLimitFilter(limiter);
        List<com.gatepulse.core.Filter> chain = List.of(filter,
                (ctx, next) -> new GatewayResponse(200, Map.of(), "ok".getBytes()));
        RequestContext ctx = RequestContext.builder().requestId("r").method("GET").path("/x")
                .clientIp("1.2.3.4").clientKey("ip:1.2.3.4").build();

        GatewayResponse first = FilterChain.execute(chain, ctx);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.header("X-RateLimit-Limit")).isEqualTo("2");
        assertThat(first.header("X-RateLimit-Remaining")).isEqualTo("1");

        FilterChain.execute(chain, ctx);
        GatewayResponse third = FilterChain.execute(chain, ctx);

        assertThat(third.status()).isEqualTo(429);
        assertThat(third.header("Retry-After")).isEqualTo("1");
        assertThat(third.header("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(new String(third.body())).contains("rate_limited");
    }
}
