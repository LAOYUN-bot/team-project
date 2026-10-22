package cn.zjnu.a02.platform.ratelimit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.db.Db;

/**
 * LLM 调用令牌桶（NFR-8，v2.0 §3.4.2「限流与费用防护」）。
 *
 * <p>四项必做里本类负责前两项：
 * <ol>
 *   <li><b>客户端令牌桶</b>：全局限在供应商额度的 70–80%
 *       （供应商 30 RPM 则自己限 22 RPM），留出安全余量；</li>
 *   <li><b>把限流当退避信号</b>：本类只返回「是否放行 + 建议等待秒数」，
 *       <b>不做即时重试</b>——即时重试会放大雪崩。调用方应降级或稍后重试。</li>
 * </ol>
 *
 * <p>另加两道兜底：单用户 RPM 上限（防单个用户刷爆额度）、日调用量硬上限（防调试循环）。
 *
 * <p>生产环境可换成 Redis 令牌桶以支持多实例；接口保持不变。
 */
@Component
public class LlmRateLimiter {

    /** 放行/拒绝结果。 */
    public record Decision(boolean allowed, String reason, long retryAfterSeconds) {
        public static Decision allow() {
            return new Decision(true, "OK", 0);
        }

        public static Decision deny(String reason, long retryAfterSeconds) {
            return new Decision(false, reason, Math.max(1, retryAfterSeconds));
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(double capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryTake(double capacity, double refillPerSecond) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized double tokens() {
            return tokens;
        }
    }

    private final int globalRpm;
    private final int userRpm;
    private final int dailyLimit;
    private final double alertRatio;
    private final Bucket globalBucket;
    private final Map<Long, Bucket> userBuckets = new ConcurrentHashMap<>();
    private final AtomicLong dailyCounter = new AtomicLong();
    private volatile java.time.LocalDate counterDate = java.time.LocalDate.now();
    private final Db db;

    public LlmRateLimiter(@Value("${a02.ai.limit.global-rpm:22}") int globalRpm,
                          @Value("${a02.ai.limit.user-rpm:8}") int userRpm,
                          @Value("${a02.ai.limit.daily-calls:1500}") int dailyLimit,
                          @Value("${a02.ai.limit.alert-ratio:0.8}") double alertRatio,
                          Db db) {
        this.globalRpm = Math.max(1, globalRpm);
        this.userRpm = Math.max(1, userRpm);
        this.dailyLimit = Math.max(1, dailyLimit);
        this.alertRatio = alertRatio;
        this.globalBucket = new Bucket(this.globalRpm);
        this.db = db;
    }

    /**
     * 申请一次调用额度。
     *
     * @return 放行则 {@code allowed=true}；否则带原因与建议等待秒数，调用方应**降级**而非重试。
     */
    public Decision tryAcquire(Long userId) {
        resetDailyIfNeeded();

        // ① 日调用量硬上限
        long used = dailyCounter.get();
        if (used >= dailyLimit) {
            return Decision.deny("已达到日调用量上限 " + dailyLimit + " 次（NFR-8 费用防护）", 3600);
        }

        // ② 单用户 RPM
        if (userId != null) {
            Bucket userBucket = userBuckets.computeIfAbsent(userId, k -> new Bucket(userRpm));
            if (!userBucket.tryTake(userRpm, userRpm / 60.0)) {
                return Decision.deny("单用户调用过于频繁（上限 " + userRpm + " 次/分钟），请稍后再试", 10);
            }
        }

        // ③ 全局 RPM（留 20% 余量由配置控制）
        if (!globalBucket.tryTake(globalRpm, globalRpm / 60.0)) {
            return Decision.deny("全局模型调用已达令牌桶上限（" + globalRpm + " 次/分钟），系统已降级", 5);
        }

        dailyCounter.incrementAndGet();
        if (dailyCounter.get() >= dailyLimit * alertRatio) {
            // 告警阈值（v2.0 §3.4.4）：只打日志提醒，不阻断
            org.slf4j.LoggerFactory.getLogger(LlmRateLimiter.class)
                    .warn("模型日调用量已达预算 {}/{}（{}%），请检查是否有调试循环",
                            dailyCounter.get(), dailyLimit,
                            Math.round(dailyCounter.get() * 100.0 / dailyLimit));
        }
        return Decision.allow();
    }

    /** 当前限流状态（供管理端展示）。 */
    public Map<String, Object> status() {
        resetDailyIfNeeded();
        return Map.of(
                "globalRpm", globalRpm,
                "userRpm", userRpm,
                "dailyLimit", dailyLimit,
                "todayCalls", dailyCounter.get(),
                "globalTokensLeft", Math.round(globalBucket.tokens() * 10) / 10.0,
                "alertRatio", alertRatio,
                "note", "客户端令牌桶限在供应商额度的 70–80%；限流时按 NFR-8 降级，不做即时重试");
    }

    public long todayCalls() {
        return dailyCounter.get();
    }

    private void resetDailyIfNeeded() {
        java.time.LocalDate today = java.time.LocalDate.now();
        if (!today.equals(counterDate)) {
            counterDate = today;
            dailyCounter.set(0);
        }
    }

    /** 供管理端读取 sys_config 中的限流配置说明（不改配置，只读）。 */
    public Map<String, Object> configView() {
        return db == null ? Map.of() : Map.of("source", "sys_config（ai.limit.*）");
    }
}
