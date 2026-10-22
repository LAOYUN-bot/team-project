package cn.zjnu.a02.ai.cost;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.common.db.Db;

/**
 * 模型调用日志与成本统计（NFR-8，v2.0 §3.4.4）。
 *
 * <p>每次调用都写 {@code llm_call_log}：模型、token（估算/实测）、耗时、错误码、缓存命中、
 * 是否降级、估算成本、traceId。这张表同时支撑三件事：
 * <ol>
 *   <li><b>成本统计</b>：日调用量、top 消耗功能、估算总花费；</li>
 *   <li><b>限流排查</b>：错误码分布（429 占比）；</li>
 *   <li><b>答辩展示</b>：把「有成本意识」变成可截图的证据。</li>
 * </ol>
 */
@Service
public class LlmCallLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogService.class);

    private final Db db;
    private final TokenEstimator tokenEstimator;

    public LlmCallLogService(Db db, TokenEstimator tokenEstimator) {
        this.db = db;
        this.tokenEstimator = tokenEstimator;
    }

    /** 一次调用的完整记录。 */
    public record CallRecord(Long userId, String scene, String provider, String model,
                             String promptVersion, int promptTokens, int completionTokens,
                             long latencyMs, boolean success, String errorCode,
                             boolean cacheHit, boolean degraded, int retryCount, String traceId) {
    }

    public void record(CallRecord record) {
        try {
            int total = record.promptTokens() + record.completionTokens();
            double inPrice = configDouble("ai.cost.unit-cny-per-1k-in", 0.001);
            double outPrice = configDouble("ai.cost.unit-cny-per-1k-out", 0.002);
            // 本地 Ollama 调用零成本，真实成本只在云端端点发生
            boolean local = record.provider() != null && record.provider().contains("local");
            double cost = local || record.cacheHit() ? 0
                    : tokenEstimator.cost(record.promptTokens(), record.completionTokens(), inPrice, outPrice);

            db.update("INSERT INTO llm_call_log(user_id, scene, provider, model, prompt_version,"
                            + " prompt_tokens, completion_tokens, total_tokens, latency_ms, success,"
                            + " error_code, cache_hit, degraded, retry_count, cost_cny, trace_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    record.userId(), record.scene(), record.provider(), record.model(),
                    record.promptVersion(), record.promptTokens(), record.completionTokens(), total,
                    record.latencyMs(), record.success() ? 1 : 0, record.errorCode(),
                    record.cacheHit() ? 1 : 0, record.degraded() ? 1 : 0,
                    record.retryCount(), cost, record.traceId());
        } catch (Exception e) {
            // 记账失败不能影响主流程（但必须留痕）
            log.warn("模型调用日志写入失败 scene={} err={}", record.scene(), e.getMessage());
        }
    }

    /** 管理端调用统计（GET /api/admin/llm-stats）。 */
    public Map<String, Object> stats(int days) {
        int span = Math.max(1, Math.min(90, days));
        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, Object> overview = db.one(
                "SELECT COUNT(*) AS calls,"
                        + " COALESCE(SUM(total_tokens),0) AS tokens,"
                        + " COALESCE(SUM(cost_cny),0) AS cost,"
                        + " COALESCE(SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),0) AS failures,"
                        + " COALESCE(SUM(CASE WHEN cache_hit=1 THEN 1 ELSE 0 END),0) AS cache_hits,"
                        + " COALESCE(SUM(CASE WHEN degraded=1 THEN 1 ELSE 0 END),0) AS degraded,"
                        + " COALESCE(AVG(latency_ms),0) AS avg_latency"
                        + " FROM llm_call_log WHERE created_at >= ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(span)));
        Map<String, Object> overviewView = db.camel(overview == null ? Map.of() : overview);

        long calls = asLong(overviewView.get("calls"));
        long failures = asLong(overviewView.get("failures"));
        long cacheHits = asLong(overviewView.get("cacheHits"));
        overviewView.put("failureRate", calls == 0 ? 0 : round(failures * 100.0 / calls));
        overviewView.put("cacheHitRate", calls == 0 ? 0 : round(cacheHits * 100.0 / calls));
        result.put("overview", overviewView);
        result.put("windowDays", span);

        result.put("byScene", db.camelList("SELECT scene, COUNT(*) AS calls,"
                + " COALESCE(SUM(total_tokens),0) AS tokens,"
                + " ROUND(COALESCE(SUM(cost_cny),0),6) AS cost"
                + " FROM llm_call_log WHERE created_at >= ? GROUP BY scene ORDER BY calls DESC",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(span))));

        result.put("byProvider", db.camelList("SELECT provider, model, COUNT(*) AS calls,"
                + " ROUND(COALESCE(SUM(cost_cny),0),6) AS cost"
                + " FROM llm_call_log WHERE created_at >= ? GROUP BY provider, model ORDER BY calls DESC",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(span))));

        result.put("byErrorCode", db.camelList("SELECT error_code, COUNT(*) AS cnt"
                + " FROM llm_call_log WHERE success=0 AND error_code IS NOT NULL"
                + " GROUP BY error_code ORDER BY cnt DESC"));

        result.put("daily", db.camelList("SELECT CAST(created_at AS DATE) AS stat_date, COUNT(*) AS calls,"
                + " COALESCE(SUM(total_tokens),0) AS tokens,"
                + " ROUND(COALESCE(SUM(cost_cny),0),6) AS cost"
                + " FROM llm_call_log WHERE created_at >= ? GROUP BY CAST(created_at AS DATE) ORDER BY stat_date",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(span))));

        long today = db.count("SELECT COUNT(*) FROM llm_call_log WHERE CAST(created_at AS DATE) = CURRENT_DATE");
        long dailyLimit = (long) configDouble("ai.limit.daily-calls", 1500);
        double alertRatio = configDouble("ai.limit.alert-ratio", 0.8);
        result.put("budget", Map.of(
                "todayCalls", today,
                "dailyLimit", dailyLimit,
                "usageRatio", round(dailyLimit == 0 ? 0 : today * 100.0 / dailyLimit),
                "alertRatio", alertRatio * 100,
                "alerting", dailyLimit > 0 && today >= dailyLimit * alertRatio));

        List<Map<String, Object>> recent = db.camelList("SELECT scene, provider, model, prompt_tokens,"
                + " completion_tokens, latency_ms, success, error_code, cache_hit, degraded, cost_cny, created_at"
                + " FROM llm_call_log ORDER BY id DESC LIMIT 20");
        result.put("recent", recent);

        result.put("note", "token 数为估算值（真实值以供应商 usage 为准）；本地 Ollama 调用与缓存命中不计成本。");
        return result;
    }

    private double configDouble(String key, double defaultValue) {
        try {
            Map<String, Object> row = db.one("SELECT cfg_value FROM sys_config WHERE cfg_key=?", key);
            if (row != null && row.get("cfg_value") != null) {
                return Double.parseDouble(String.valueOf(row.get("cfg_value")));
            }
        } catch (Exception ignored) {
            // 配置缺失时用默认值
        }
        return defaultValue;
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
