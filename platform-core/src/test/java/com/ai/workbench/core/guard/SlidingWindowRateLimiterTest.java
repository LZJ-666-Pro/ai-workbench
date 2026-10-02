package com.ai.workbench.core.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 滑动窗口限流器的行为测试。
 * 时钟通过构造函数注入，直接推进时间验证窗口语义，不依赖 sleep——
 * 用 sleep 的限流测试要么慢、要么在慢机器上随机失败。
 */
class SlidingWindowRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private SlidingWindowRateLimiter limiter(int limit, Duration window) {
        return new SlidingWindowRateLimiter(limit, window, now::get);
    }

    @Test
    @DisplayName("窗口内放行到配额上限，第 limit+1 次被拒")
    void rejectsAfterLimit() {
        SlidingWindowRateLimiter limiter = limiter(3, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).as("第 4 次应被拒").isFalse();
        assertThat(limiter.usedCount("s1")).isEqualTo(3);
    }

    @Test
    @DisplayName("窗口滑过后重新放行，且不会一次性恢复全部配额")
    void windowSlidesGradually() {
        SlidingWindowRateLimiter limiter = limiter(2, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("s1")).isTrue();
        now.addAndGet(30_000);                       // +30s
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isFalse();

        // 再走 31s：第一次请求（t=0）已滑出窗口，第二次（t=30s）还在，只能再放 1 次。
        // 固定窗口实现在这里会整段重置、一口气放行 2 次——那正是本类要避免的边界突发。
        now.addAndGet(31_000);
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isFalse();
    }

    @Test
    @DisplayName("窗口完全过去后配额恢复")
    void recoversAfterFullWindow() {
        SlidingWindowRateLimiter limiter = limiter(1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isFalse();

        now.addAndGet(60_000);
        assertThat(limiter.tryAcquire("s1")).isTrue();
    }

    @Test
    @DisplayName("不同 key 互不影响（一个会话刷爆不影响别的会话）")
    void keysAreIndependent() {
        SlidingWindowRateLimiter limiter = limiter(1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isFalse();
        assertThat(limiter.tryAcquire("s2")).as("另一个会话不应被牵连").isTrue();
    }

    @Test
    @DisplayName("并发调用下放行次数不超过配额")
    void concurrentAcquiresRespectLimit() throws Exception {
        SlidingWindowRateLimiter limiter = limiter(50, Duration.ofMinutes(1));
        int threads = 16;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger allowed =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        if (limiter.tryAcquire("hot")) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        // 1600 次尝试、配额 50：无论线程如何交错，放行数必须恰好等于配额
        assertThat(allowed.get()).isEqualTo(50);
    }
}
