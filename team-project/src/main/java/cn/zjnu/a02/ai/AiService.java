package cn.zjnu.a02.ai;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.ai.adapter.MockAiAdapter;
import cn.zjnu.a02.ai.adapter.TboxAiAdapter;
import cn.zjnu.a02.ai.prompt.PromptLoader;
import cn.zjnu.a02.ai.privacy.PiiRedactor;
import cn.zjnu.a02.ai.schema.FallbackTemplates;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.port.AiChatPort;
import cn.zjnu.a02.common.port.AiChatPort.ChatRequest;
import cn.zjnu.a02.common.port.AiChatPort.Message;
import cn.zjnu.a02.common.port.AiChatPort.StructuredRequest;
import cn.zjnu.a02.common.port.AiChatPort.StructuredResult;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.platform.ratelimit.AiRateLimiter;

/**
 * AI 能力统一门面（架构文档 §7.2）。
 *
 * <p>业务模块只调用本类，不感知提供方差异。本类负责：
 * <ol>
 *   <li>限流（保护 Token 配额）；</li>
 *   <li>上下文脱敏（敏感信息不出内网）；</li>
 *   <li>模型路由与**自动降级**：tbox 失败 → mock，并标记 {@code degraded}；</li>
 *   <li>生成日志落库（可追溯）。</li>
 * </ol>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final MockAiAdapter mockAdapter;
    private final TboxAiAdapter tboxAdapter;
    private final PromptLoader prompts;
    private final PiiRedactor redactor;
    private final AiRateLimiter rateLimiter;
    private final AiGenerationLogService generationLog;
    private final JsonUtils json;
    private final String providerConfig;
    private final boolean fallbackEnabled;

    public AiService(MockAiAdapter mockAdapter,
                     TboxAiAdapter tboxAdapter,
                     PromptLoader prompts,
                     PiiRedactor redactor,
                     AiRateLimiter rateLimiter,
                     AiGenerationLogService generationLog,
                     JsonUtils json,
                     @Value("${a02.ai.provider:mock}") String providerConfig,
                     @Value("${a02.ai.fallback-enabled:true}") boolean fallbackEnabled) {
        this.mockAdapter = mockAdapter;
        this.tboxAdapter = tboxAdapter;
        this.prompts = prompts;
        this.redactor = redactor;
        this.rateLimiter = rateLimiter;
        this.generationLog = generationLog;
        this.json = json;
        this.providerConfig = providerConfig;
        this.fallbackEnabled = fallbackEnabled;
    }

    public String provider() {
        if ("tbox".equalsIgnoreCase(providerConfig) && tboxAdapter.configured()) {
            return "tbox";
        }
        return "mock";
    }

    public boolean tboxConfigured() {
        return tboxAdapter.configured();
    }

    // ------------------------------------------------------------------
    // 结构化输出
    // ------------------------------------------------------------------

    /**
     * 结构化生成：内部完成 Schema 校验、修复重试与兜底。
     *
     * @param scene    场景（path/unit/trend/score/incentive/resume）
     * @param userId   用户
     * @param context  业务上下文（本方法会自动脱敏）
     * @param fallback 兜底模板
     */
    public StructuredResult structured(String scene, Long userId,
                                       Map<String, Object> context, String fallback) {
        rateLimiter.acquire(userId == null ? 0L : userId);
        Map<String, Object> safeContext = redactor.redact(context);
        String systemPrompt = prompts.get("persona");
        String taskPrompt = prompts.get(inSupportedScenes(scene) ? scene : "persona");

        StructuredRequest request = new StructuredRequest(
                scene, userId, systemPrompt, taskPrompt, safeContext,
                List.of(), fallback == null ? FallbackTemplates.forScene(scene, safeContext) : fallback);

        StructuredResult result;
        if ("tbox".equals(provider())) {
            try {
                result = tboxAdapter.structured(request);
            } catch (BizException e) {
                if (!fallbackEnabled) {
                    throw e;
                }
                log.warn("百宝箱不可用，降级到本地 Mock：{}", e.getMessage());
                result = mockAdapter.structured(new StructuredRequest(scene, userId, systemPrompt,
                        taskPrompt, withDegraded(safeContext), List.of(), request.fallback()));
            }
        } else {
            result = mockAdapter.structured(request);
        }

        generationLog.record(userId, scene, result.provider(), result.model(),
                result.promptVersion(), result.latencyMs(), result.retryCount(), result.degraded());
        return result;
    }

    // ------------------------------------------------------------------
    // 文本对话
    // ------------------------------------------------------------------

    public ChatResultView chat(String scene, Long userId, Map<String, Object> context,
                               List<Message> messages, String systemPromptOverride) {
        rateLimiter.acquire(userId == null ? 0L : userId);
        Map<String, Object> safeContext = redactor.redact(context);
        String systemPrompt = systemPromptOverride == null ? prompts.get("persona") : systemPromptOverride;
        ChatRequest request = ChatRequest.of(scene, userId, systemPrompt, safeContext, messages);

        long start = System.currentTimeMillis();
        AiChatPort.ChatResult result;
        if ("tbox".equals(provider())) {
            try {
                result = tboxAdapter.chat(request);
            } catch (BizException e) {
                if (!fallbackEnabled) {
                    throw e;
                }
                log.warn("百宝箱对话不可用，降级到本地 Mock：{}", e.getMessage());
                result = mockAdapter.chat(request);
                generationLog.record(userId, scene, "mock", result.model(),
                        prompts.version("persona"), System.currentTimeMillis() - start, 0, true);
                return new ChatResultView(result.content(), true, "mock", result.model());
            }
        } else {
            result = mockAdapter.chat(request);
        }
        generationLog.record(userId, scene, result.provider(), result.model(),
                prompts.version("persona"), result.latencyMs(), 0, result.degraded());
        return new ChatResultView(result.content(), result.degraded(), result.provider(), result.model());
    }

    /** 流式对话：逐块回调，首字延迟即为 NFR-1 观测点。 */
    public void stream(String scene, Long userId, Map<String, Object> context,
                       List<Message> messages, String systemPromptOverride,
                       Consumer<String> onChunk) {
        rateLimiter.acquire(userId == null ? 0L : userId);
        Map<String, Object> safeContext = redactor.redact(context);
        String systemPrompt = systemPromptOverride == null ? prompts.get("persona") : systemPromptOverride;
        ChatRequest request = ChatRequest.of(scene, userId, systemPrompt, safeContext, messages);

        long start = System.currentTimeMillis();
        if ("tbox".equals(provider())) {
            try {
                tboxAdapter.stream(request, onChunk);
                generationLog.record(userId, scene, "tbox", tboxAdapter.provider(),
                        prompts.version("persona"), System.currentTimeMillis() - start, 0, false);
                return;
            } catch (BizException e) {
                if (!fallbackEnabled) {
                    throw e;
                }
                log.warn("百宝箱流式不可用，降级到本地 Mock：{}", e.getMessage());
            }
        }
        mockAdapter.stream(request, onChunk);
        generationLog.record(userId, scene, "mock", "local-rule-template-v1",
                prompts.version("persona"), System.currentTimeMillis() - start, 0, false);
    }

    /** 场景角色扮演：注入角色卡与场景说明（模拟训练用）。 */
    public void streamRolePlay(Long userId, Map<String, Object> context, List<Message> messages,
                               Consumer<String> onChunk) {
        String systemPrompt = prompts.render("coach", Map.of(
                "role_card", String.valueOf(context.getOrDefault("roleCard", "")),
                "scenario", String.valueOf(context.getOrDefault("scenarioDesc", ""))));
        stream("coach", userId, context, messages, systemPrompt, onChunk);
    }

    public String renderCoachPrompt(Map<String, Object> context) {
        return prompts.render("coach", Map.of(
                "role_card", String.valueOf(context.getOrDefault("roleCard", "")),
                "scenario", String.valueOf(context.getOrDefault("scenarioDesc", ""))));
    }

    /** 提示词原文（用于「提示词版本化」的可视化证据）。 */
    public Map<String, Object> promptCatalog() {
        List<Map<String, Object>> items = List.of(
                catalogItem("persona"), catalogItem("path"), catalogItem("unit"),
                catalogItem("trend"), catalogItem("score"), catalogItem("incentive"),
                catalogItem("coach"));
        return Map.of("items", items,
                "note", "提示词存于 resources/prompts/*.txt 并纳入 Git；版本号随生成结果落 ai_generation_log。");
    }

    private Map<String, Object> catalogItem(String name) {
        String content = prompts.get(name);
        return Map.of("name", name, "version", prompts.version(name),
                "chars", content.length(),
                "preview", content.length() > 400 ? content.substring(0, 400) + "…" : content);
    }

    private Map<String, Object> withDegraded(Map<String, Object> context) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(context);
        copy.put("__degraded", true);
        return copy;
    }

    private boolean inSupportedScenes(String scene) {
        return FallbackTemplates.supportedScenes().contains(scene);
    }

    /** 对话结果视图（含降级标识，前端据此展示「结果仅供参考」）。 */
    public record ChatResultView(String content, boolean degraded, String provider, String model) {
    }

    /** 便于接口层输出原始 JSON（调试与答辩演示用）。 */
    public String rawJson(Object value) {
        return json.stringify(value);
    }
}
