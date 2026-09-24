package cn.zjnu.a02.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.common.db.Db;

/**
 * AI 生成日志（架构文档 §7.4 步骤 [6]、NFR-4 责任归属）。
 *
 * <p>每次生成记录：场景、提供方、模型、提示词版本、耗时、重试次数、是否降级。
 * 这是「AI 产出可追溯」的证据，也是专项材料里「提示词版本化」的运行时体现。
 */
@Service
public class AiGenerationLogService {

    private static final Logger log = LoggerFactory.getLogger(AiGenerationLogService.class);

    private final Db db;

    public AiGenerationLogService(Db db) {
        this.db = db;
    }

    public void record(Long userId, String scene, String provider, String model, String promptVersion,
                       long latencyMs, int retryCount, boolean degraded) {
        try {
            db.update("INSERT INTO ai_generation_log"
                            + "(user_id, scene, provider, model, prompt_version, latency_ms, retry_count, degraded)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    userId, scene, provider, model, promptVersion, latencyMs, retryCount, degraded ? 1 : 0);
        } catch (Exception e) {
            log.warn("AI 生成日志写入失败 scene={} err={}", scene, e.getMessage());
        }
    }
}
