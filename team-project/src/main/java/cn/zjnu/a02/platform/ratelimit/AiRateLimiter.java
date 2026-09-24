package cn.zjnu.a02.platform.ratelimit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;

/**
 * 按用户的 AI 调用限流（架构文档 §7.2，保护 Token 配额 R-2）。
 *
 * <p>Demo 使用进程内滑动窗口；生产替换为 Redis 令牌桶即可，接口不变。
 */
@Component
public class AiRateLimiter {

    private final int limitPerMinute;
    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    public AiRateLimiter(@Value("${a02.ratelimit.ai-per-minute:30}") int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    public void acquire(long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 60_000L) {
                window.pollFirst();
            }
            if (window.size() >= limitPerMinute) {
                throw new BizException(ErrorCode.RATE_LIMITED,
                        "AI 调用超过每分钟 " + limitPerMinute + " 次限制，请稍后再试");
            }
            window.addLast(now);
        }
    }
}
