package cn.zjnu.a02.common.port;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 能力端口（架构文档 §7.2 AD-2）。
 *
 * <p>业务模块只依赖本接口，不直接拼接大模型 HTTP 请求。实现可切换：
 * {@code mock}（本地规则模板，离线可用）/ {@code tbox}（蚂蚁百宝箱企业版）。
 */
public interface AiChatPort {

    /** 单轮/多轮对话（非流式）。 */
    ChatResult chat(ChatRequest request);

    /** 流式对话：逐块回调，满足 NFR-1「LLM 流式首字 ≤2s」。 */
    void stream(ChatRequest request, Consumer<String> onChunk);

    /**
     * 结构化输出（AD-3）：内部完成 JSON 提取 → Schema 校验 → 修复重试 ≤N → 兜底模板。
     *
     * @param request 结构化请求（场景、Schema、上下文）
     * @return 结构化结果，含 degraded / retryCount 等可审计字段
     */
    StructuredResult structured(StructuredRequest request);

    /** 当前提供方标识（mock / tbox）。 */
    String provider();

    // ------------------------------------------------------------------
    // 请求/响应模型
    // ------------------------------------------------------------------

    /**
     * 对话请求。
     *
     * @param scene      场景标识（path/coach/score/trend/incentive/persona…），用于选择提示词与温度
     * @param userId     用户 ID（用于审计与限流）
     * @param systemPrompt 系统提示词（人设 + 边界 + Guard）
     * @param context    结构化上下文（画像、雷达、目标岗位等，已脱敏）
     * @param messages   历史消息（role/content）
     */
    record ChatRequest(String scene,
                       Long userId,
                       String systemPrompt,
                       Map<String, Object> context,
                       List<Message> messages) {

        public static ChatRequest of(String scene, Long userId, String systemPrompt,
                                     Map<String, Object> context, List<Message> messages) {
            return new ChatRequest(scene, userId, systemPrompt, context, messages);
        }
    }

    record Message(String role, String content) {
        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        public static Message system(String content) {
            return new Message("system", content);
        }
    }

    record ChatResult(String content, String provider, String model, long latencyMs, boolean degraded) {
    }

    /**
     * 结构化请求。
     *
     * @param scene       场景（path/score/trend/resume…）
     * @param userId      用户
     * @param systemPrompt 系统提示词
     * @param taskPrompt  任务指令（含粒度约束）
     * @param context     结构化上下文
     * @param requiredKeys 必填字段（第一层 JSON Schema 校验：键存在 + 非空）
     * @param fallback    兜底模板（LLM 连续失败时使用，仍满足业务最低要求）
     */
    record StructuredRequest(String scene,
                             Long userId,
                             String systemPrompt,
                             String taskPrompt,
                             Map<String, Object> context,
                             List<String> requiredKeys,
                             String fallback) {
    }

    /** 结构化结果：raw 为模型原始文本，data 为校验通过后的结构化数据。 */
    record StructuredResult(String raw,
                            Map<String, Object> data,
                            String provider,
                            String model,
                            String promptVersion,
                            int retryCount,
                            boolean degraded,
                            boolean fallbackUsed,
                            long latencyMs,
                            String validationError) {
    }
}
