package cn.zjnu.a02.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.ai.adapter.MockAiAdapter;
import cn.zjnu.a02.ai.adapter.OpenAiCompatibleAiAdapter;
import cn.zjnu.a02.ai.cost.LlmCallLogService;
import cn.zjnu.a02.ai.cost.TokenEstimator;
import cn.zjnu.a02.ai.gateway.ModelRouter;
import cn.zjnu.a02.ai.prompt.PromptLoader;
import cn.zjnu.a02.ai.privacy.PiiRedactor;
import cn.zjnu.a02.ai.schema.FallbackTemplates;
import cn.zjnu.a02.ai.schema.JsonExtractor;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.TraceIds;
import cn.zjnu.a02.common.port.AiChatPort;
import cn.zjnu.a02.common.port.AiChatPort.ChatRequest;
import cn.zjnu.a02.common.port.AiChatPort.Message;
import cn.zjnu.a02.common.port.AiChatPort.StructuredRequest;
import cn.zjnu.a02.common.port.AiChatPort.StructuredResult;
import cn.zjnu.a02.common.util.CommonUtils;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.platform.ratelimit.LlmRateLimiter;

/**
 * AI 能力统一门面（FR-7.1，v2.0 重写）。
 *
 * <p>业务模块只调用本类，不感知提供方与厂商差异。本类负责 v2.0 §3.4 要求的四件事：
 * <ol>
 *   <li><b>路由与降级</b>：mock（离线模板）/ openai（真实模型，端点由 tier 决定），
 *       真实通道失败自动降级到 Mock；</li>
 *   <li><b>限流</b>：调用前过令牌桶，超限时<b>降级返回</b>而不是抛错（NFR-8）；</li>
 *   <li><b>二级缓存</b>：相同场景 + 相同上下文的请求直接命中，减少调用次数（§3.4.2 ③）；</li>
 *   <li><b>成本记账</b>：每次调用写 {@code llm_call_log}（token/耗时/错误码/缓存/降级）。</li>
 * </ol>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final MockAiAdapter mockAdapter;
    private final OpenAiCompatibleAiAdapter remoteAdapter;
    private final PromptLoader prompts;
    private final PiiRedactor redactor;
    private final LlmRateLimiter rateLimiter;
    private final LlmCallLogService callLog;
    private final TokenEstimator tokenEstimator;
    private final ModelRouter modelRouter;
    private final JsonUtils json;

    private final String providerConfig;
    private final boolean fallbackEnabled;
    private final boolean limitApplyToMock;
    private final boolean cacheEnabled;
    private final long cacheTtlMillis;

    /** 结构化结果的二级缓存：key = scene + 上下文指纹。 */
    private final Map<String, CachedStructured> cache = new ConcurrentHashMap<>();

    private record CachedStructured(Map<String, Object> data, String promptVersion,
                                    String provider, String model, long expireAt) {
        boolean valid() {
            return System.currentTimeMillis() < expireAt;
        }
    }

    public AiService(MockAiAdapter mockAdapter,
                     OpenAiCompatibleAiAdapter remoteAdapter,
                     PromptLoader prompts,
                     PiiRedactor redactor,
                     LlmRateLimiter rateLimiter,
                     LlmCallLogService callLog,
                     TokenEstimator tokenEstimator,
                     ModelRouter modelRouter,
                     JsonUtils json,
                     @Value("${a02.ai.provider:mock}") String providerConfig,
                     @Value("${a02.ai.fallback-enabled:true}") boolean fallbackEnabled,
                     @Value("${a02.ai.limit.apply-to-mock:false}") boolean limitApplyToMock,
                     @Value("${a02.ai.cache.enabled:true}") boolean cacheEnabled,
                     @Value("${a02.ai.cache.ttl-seconds:600}") long cacheTtlSeconds) {
        this.mockAdapter = mockAdapter;
        this.remoteAdapter = remoteAdapter;
        this.prompts = prompts;
        this.redactor = redactor;
        this.rateLimiter = rateLimiter;
        this.callLog = callLog;
        this.tokenEstimator = tokenEstimator;
        this.modelRouter = modelRouter;
        this.json = json;
        this.providerConfig = providerConfig;
        this.fallbackEnabled = fallbackEnabled;
        this.limitApplyToMock = limitApplyToMock;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtlMillis = Math.max(0, cacheTtlSeconds) * 1000;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public String provider() {
        if ("openai".equalsIgnoreCase(providerConfig) && remoteAdapter.configured()) {
            return "openai";
        }
        return "mock";
    }

    /** 是否具备真实模型调用条件。 */
    public boolean remoteConfigured() {
        return remoteAdapter.configured();
    }

    /** 网关与分层信息（管理端展示）。 */
    public Map<String, Object> gatewayInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider());
        result.put("providerConfig", providerConfig);
        result.put("remoteConfigured", remoteAdapter.configured());
        result.put("fallbackEnabled", fallbackEnabled);
        result.put("limitApplyToMock", limitApplyToMock);
        result.put("cacheEnabled", cacheEnabled);
        result.put("cacheTtlSeconds", cacheTtlMillis / 1000);
        result.put("cacheEntries", cache.size());
        result.put("routing", modelRouter.describe());
        result.put("limit", rateLimiter.status());
        result.put("degradePolicy",
                "真实通道失败或限流时降级为本地模板/缓存结果，返回 degraded=true，前端提示「已降级」，演示不中断");
        return result;
    }

    // ------------------------------------------------------------------
    // 结构化输出
    // ------------------------------------------------------------------

    public StructuredResult structured(String scene, Long userId,
                                       Map<String, Object> context, String fallback) {
        long start = System.currentTimeMillis();
        Map<String, Object> safeContext = redactor.redact(context);
        String fallbackJson = fallback == null ? FallbackTemplates.forScene(scene, safeContext) : fallback;

        // ① 限流（NFR-8）：超限直接降级，不抛错
        if (!acquire(userId)) {
            return degradedResult(scene, userId, fallbackJson, "RATE_LIMITED",
                    "模型调用触发限流，已返回本地模板结果（degraded）", start);
        }

        // ② 二级缓存（§3.4.2 ③ 减少调用次数）
        String cacheKey = cacheKey(scene, safeContext);
        if (cacheEnabled) {
            CachedStructured cached = cache.get(cacheKey);
            if (cached != null && cached.valid()) {
                callLog.record(new LlmCallLogService.CallRecord(userId, scene, cached.provider(),
                        cached.model(), cached.promptVersion(), 0, 0,
                        System.currentTimeMillis() - start, true, null, true, false, 0, TraceIds.current()));
                return new StructuredResult(null, cached.data(), cached.provider(), cached.model(),
                        cached.promptVersion(), 0, false, false,
                        System.currentTimeMillis() - start, null);
            }
        }

        String systemPrompt = prompts.get("persona");
        String taskPrompt = prompts.get(inSupportedScene(scene) ? scene : "persona");
        StructuredRequest request = new StructuredRequest(
                scene, userId, systemPrompt, taskPrompt, safeContext, List.of(), fallbackJson);

        StructuredResult result;
        if ("openai".equals(provider())) {
            try {
                result = remoteAdapter.structured(request);
            } catch (BizException e) {
                if (!fallbackEnabled) {
                    throw e;
                }
                log.warn("真实模型不可用，降级到本地 Mock：{}", e.getMessage());
                result = markDegraded(mockAdapter.structured(new StructuredRequest(scene, userId,
                        systemPrompt, taskPrompt, withDegraded(safeContext), List.of(), fallbackJson)),
                        "REMOTE_FAILED: " + e.getMessage());
            }
        } else {
            result = mockAdapter.structured(request);
        }

        logStructured(userId, scene, result, start);

        // ③ 只缓存「成功且未降级」的结果，避免把兜底模板写进缓存
        if (cacheEnabled && !result.degraded() && result.data() != null && !result.data().isEmpty()) {
            cache.put(cacheKey, new CachedStructured(result.data(), result.promptVersion(),
                    result.provider(), result.model(), System.currentTimeMillis() + cacheTtlMillis));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 文本对话
    // ------------------------------------------------------------------

    public ChatResultView chat(String scene, Long userId, Map<String, Object> context,
                               List<Message> messages, String systemPromptOverride) {
        long start = System.currentTimeMillis();
        if (!acquire(userId)) {
            logLimited(userId, scene, start);
            return new ChatResultView(
                    "模型调用当前受限（限流），请稍后重试。你可以先查看已有的学习计划与评分结果。",
                    true, "degraded", "rate-limited");
        }

        Map<String, Object> safeContext = redactor.redact(context);
        String systemPrompt = systemPromptOverride == null ? prompts.get("persona") : systemPromptOverride;
        ChatRequest request = ChatRequest.of(scene, userId, systemPrompt, safeContext, messages);

        AiChatPort.ChatResult result;
        if ("openai".equals(provider())) {
            try {
                result = remoteAdapter.chat(request);
            } catch (BizException e) {
                if (!fallbackEnabled) {
                    throw e;
                }
                log.warn("真实模型对话不可用，降级到本地 Mock：{}", e.getMessage());
                result = mockAdapter.chat(request);
                logChat(userId, scene, result, start, true);
                return new ChatResultView(result.content(), true, "mock", result.model());
            }
        } else {
            result = mockAdapter.chat(request);
        }
        logChat(userId, scene, result, start, result.degraded());
        return new ChatResultView(result.content(), result.degraded(), result.provider(), result.model());
    }

    public void stream(String scene, Long userId, Map<String, Object> context,
                       List<Message> messages, String systemPromptOverride,
                       Consumer<String> onChunk) {
        long start = System.currentTimeMillis();
        if (!acquire(userId)) {
            onChunk.accept("模型调用当前受限（限流），请稍后重试。");
            logLimited(userId, scene, start);
            return;
        }
        Map<String, Object> safeContext = redactor.redact(context);
        String systemPrompt = systemPromptOverride == null ? prompts.get("persona") : systemPromptOverride;
        ChatRequest request = ChatRequest.of(scene, userId, systemPrompt, safeContext, messages);

        if ("openai".equals(provider())) {
            try {
                remoteAdapter.stream(request, onChunk);
                logStream(userId, scene, start, "openai", modelRouter.current().model());
                return;
            } catch (BizException e) {
                if (!fallbackEnabled) {
                    throw e;
                }
                log.warn("真实模型流式不可用，降级到本地 Mock：{}", e.getMessage());
            }
        }
        mockAdapter.stream(request, onChunk);
        logStream(userId, scene, start, "mock", "local-rule-template-v1");
    }

    /** 场景角色扮演：注入角色卡与场景说明（模拟训练用）。 */
    public void streamRolePlay(Long userId, Map<String, Object> context, List<Message> messages,
                               Consumer<String> onChunk) {
        stream("coach", userId, context, messages, renderCoachPrompt(context), onChunk);
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
                catalogItem("resume"), catalogItem("coach"), catalogItem("rag"));
        return Map.of("items", items,
                "note", "提示词存于 resources/prompts/*.txt 并纳入 Git；版本号随生成结果落 llm_call_log。");
    }

    private Map<String, Object> catalogItem(String name) {
        String content = prompts.get(name);
        return Map.of("name", name, "version", prompts.version(name),
                "chars", content.length(),
                "preview", content.length() > 400 ? content.substring(0, 400) + "…" : content);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private boolean acquire(Long userId) {
        // Mock 通道不消耗额度，默认不参与限流（否则演示会被自己限住）
        if ("mock".equals(provider()) && !limitApplyToMock) {
            return true;
        }
        LlmRateLimiter.Decision decision = rateLimiter.tryAcquire(userId);
        if (!decision.allowed()) {
            log.warn("模型调用被限流：{}（建议等待 {}s）", decision.reason(), decision.retryAfterSeconds());
        }
        return decision.allowed();
    }

    private String cacheKey(String scene, Map<String, Object> context) {
        return scene + ":" + CommonUtils.fingerprint(scene + "|" + json.stringify(context));
    }

    private StructuredResult degradedResult(String scene, Long userId, String fallbackJson,
                                            String errorCode, String message, long start) {
        Map<String, Object> data = json.parseMap(JsonExtractor.extract(fallbackJson));
        String promptVersion = prompts.version(fallbackSceneName(scene));
        callLog.record(new LlmCallLogService.CallRecord(userId, scene, "degraded", "fallback-template",
                promptVersion, 0, 0, System.currentTimeMillis() - start,
                true, errorCode, false, true, 0, TraceIds.current()));
        return new StructuredResult(null, data, "degraded", "fallback-template",
                promptVersion, 0, true, true, System.currentTimeMillis() - start, message);
    }

    private StructuredResult markDegraded(StructuredResult result, String error) {
        return new StructuredResult(result.raw(), result.data(), result.provider(), result.model(),
                result.promptVersion(), result.retryCount(), true, result.fallbackUsed(),
                result.latencyMs(), error);
    }

    private void logStructured(Long userId, String scene, StructuredResult result, long start) {
        int promptTokens = tokenEstimator.estimate(scene);
        int completionTokens = tokenEstimator.estimate(result.raw() == null ? "" : result.raw());
        callLog.record(new LlmCallLogService.CallRecord(userId, scene, result.provider(), result.model(),
                result.promptVersion(), promptTokens, completionTokens,
                System.currentTimeMillis() - start, true, null, false, result.degraded(),
                result.retryCount(), TraceIds.current()));
    }

    private void logChat(Long userId, String scene, AiChatPort.ChatResult result, long start, boolean degraded) {
        callLog.record(new LlmCallLogService.CallRecord(userId, scene, result.provider(), result.model(),
                prompts.version("persona"), tokenEstimator.estimate(scene),
                tokenEstimator.estimate(result.content()),
                System.currentTimeMillis() - start, true, null, false, degraded, 0, TraceIds.current()));
    }

    private void logStream(Long userId, String scene, long start, String provider, String model) {
        callLog.record(new LlmCallLogService.CallRecord(userId, scene, provider, model,
                prompts.version("persona"), 0, 0, System.currentTimeMillis() - start,
                true, null, false, false, 0, TraceIds.current()));
    }

    private void logLimited(Long userId, String scene, long start) {
        callLog.record(new LlmCallLogService.CallRecord(userId, scene, "degraded", "rate-limited",
                prompts.version("persona"), 0, 0, System.currentTimeMillis() - start,
                true, "RATE_LIMITED", false, true, 0, TraceIds.current()));
    }

    private Map<String, Object> withDegraded(Map<String, Object> context) {
        Map<String, Object> copy = new LinkedHashMap<>(context);
        copy.put("__degraded", true);
        return copy;
    }

    private boolean inSupportedScene(String scene) {
        return FallbackTemplates.supportedScenes().contains(scene);
    }

    private String fallbackSceneName(String scene) {
        return inSupportedScene(scene) ? scene : "persona";
    }

    /** 对话结果视图（含降级标识，前端据此展示「结果仅供参考」）。 */
    public record ChatResultView(String content, boolean degraded, String provider, String model) {
    }

    /** 供接口层输出原始 JSON（调试与答辩演示用）。 */
    public String rawJson(Object value) {
        return json.stringify(value);
    }
}
