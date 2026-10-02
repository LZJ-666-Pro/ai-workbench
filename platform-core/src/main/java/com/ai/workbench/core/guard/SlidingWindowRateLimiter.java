package com.ai.workbench.core.guard;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 滑动窗口限流器（进程内，单实例）。
 *
 * 为什么用滑动窗口而不是固定窗口计数：固定窗口在窗口边界处允许两倍突发
 * （59 秒打满一轮、下一秒再打满一轮），限流形同虚设。滑动窗口记录每次请求的
 * 时间戳，任意时刻都保证「过去一个窗口内不超过 limit 次」。
 *
 * 为什么是进程内而不是 Redis：本项目目前是单实例部署，Redis 在 compose 里只是预留。
 * 引入 Redis 限流会多一个必须可用的外部依赖——限流器本身挂掉导致的故障比限流失效更严重。
 * 多实例部署时这里必须换成 Redis 或网关层限流，届时本类即为其本地降级实现。
 *
 * 时间源通过构造函数注入（{@link LongSupplier}），使窗口行为可在测试中直接推进时钟验证，
 * 不必真的 sleep。
 */
public class SlidingWindowRateLimiter {

    /** 登记表超过这个规模就清理一次过期窗口，避免长期运行下无界增长 */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public SlidingWindowRateLimiter(int limit, Duration window) {
        this(limit, window, System::currentTimeMillis);
    }

    public SlidingWindowRateLimiter(int limit, Duration window, LongSupplier clock) {
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /** 尝试放行一次；返回 false 表示该 key 在窗口内已用满配额 */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (hits.size() > SWEEP_THRESHOLD) {
            sweep(now);
        }
        Deque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            prune(window, now);
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /** 该 key 在窗口内已用次数（观测与测试用） */
    public int usedCount(String key) {
        Deque<Long> window = hits.get(key);
        if (window == null) {
            return 0;
        }
        synchronized (window) {
            prune(window, clock.getAsLong());
            return window.size();
        }
    }

    private void prune(Deque<Long> window, long now) {
        while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) {
            window.pollFirst();
        }
    }

    /**
     * 清理过期窗口。与 tryAcquire 之间没有全局锁，理论上存在「刚被清掉又立刻重建」
     * 的竞态，最坏结果是窗口短暂重置、多放行一次——限流场景下可接受，
     * 比为此加全局锁（把限流变成串行瓶颈）划算。
     */
    private void sweep(long now) {
        hits.entrySet().removeIf(entry -> {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                prune(window, now);
                return window.isEmpty();
            }
        });
    }
}
