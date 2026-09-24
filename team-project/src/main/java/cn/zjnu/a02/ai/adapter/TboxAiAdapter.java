package cn.zjnu.a02.ai.adapter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.ai.prompt.PromptLoader;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.port.AiChatPort;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * 蚂蚁百宝箱企业版适配器（架构文档 §7.1 / T5 平台适配度加分核心）。
 *
 * <p>职责边界（写进企业专项材料①）：智能体负责**推理与生成**，
 * 后端负责**校验、持久化、权限与兜底**；智能体不直接写库。
 *
 * <p>调用前上下文已由 {@code PiiRedactor} 脱敏；未配置 {@code TBOX_API_KEY} 时
 * 明确抛出「外部服务不可用」，由上层 {@code AiService} 决定是否降级到 Mock，
 * **不抛栈、不返回半成品**。
 */
@Component
public class TboxAiAdapter extends AbstractStructuredAiAdapter {

    private static final Logger log = LoggerFactory.getLogger(TboxAiAdapter.class);

    private final String baseUrl;
    private final String apiKey;
    private final String agentId;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final double pathTemperature;
    private final double incentiveTemperature;

    public TboxAiAdapter(PromptLoader prompts,
                         JsonUtils json,
                         @Value("${a02.ai.max-retry:2}") int maxRetry,
                         @Value("${a02.ai.tbox.base-url}") String baseUrl,
                         @Value("${a02.ai.tbox.api-key}") String apiKey,
                         @Value("${a02.ai.tbox.agent-id}") String agentId,
                         @Value("${a02.ai.temperature.path:0.3}") double pathTemperature,
                         @Value("${a02.ai.temperature.incentive:0.8}") double incentiveTemperature) {
        super(prompts, json, maxRetry);
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.agentId = agentId;
        this.timeout = Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.pathTemperature = pathTemperature;
        this.incentiveTemperature = incentiveTemperature;
    }

    @Override
    public String provider() {
        return "tbox";
    }

    @Override
    protected String modelName() {
        return "tbox-agent-" + (agentId == null || agentId.isBlank() ? "unset" : agentId);
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && agentId != null && !agentId.isBlank();
    }

    /** 温度按场景分离（架构文档 §3.3：路径求稳 0.3、激励求多样 0.8）。 */
    private double temperatureFor(String scene) {
        return "incentive".equals(scene) || "persona".equals(scene) ? incentiveTemperature : pathTemperature;
    }

    @Override
    protected String rawStructured(StructuredRequest request, String repairHint) {
        // 结构化场景：把「上一轮校验失败原因」回灌给模型，否则重试无意义（架构文档 §3.3）
        String prompt = request.taskPrompt();
        if (repairHint != null) {
            prompt = prompt + "\n\n【上一次输出未通过校验，原因：" + repairHint
                    + "。请严格按 JSON 格式重新输出，不要包含解释文字。】";
        }
        return invoke(prompt, request.context(), request.scene());
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        String content = invoke(buildChatPrompt(request), request.context(), request.scene());
        return new ChatResult(content, provider(), modelName(),
                System.currentTimeMillis() - start, false);
    }

    @Override
    public void stream(ChatRequest request, Consumer<String> onChunk) {
        // 平台侧流式接口未在离线环境验证；此处按整段回调，由前端逐字动画补偿
        String content = invoke(buildChatPrompt(request), request.context(), request.scene());
        for (int i = 0; i < content.length(); i += 4) {
            onChunk.accept(content.substring(i, Math.min(content.length(), i + 4)));
        }
    }

    private String buildChatPrompt(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.systemPrompt() == null ? "" : request.systemPrompt()).append("\n\n");
        sb.append("【用户上下文】\n").append(json.stringify(request.context())).append("\n\n");
        sb.append("【对话记录】\n");
        if (request.messages() != null) {
            for (AiChatPort.Message m : request.messages()) {
                sb.append(m.role()).append(": ").append(m.content()).append('\n');
            }
        }
        sb.append("\n请以角色身份回复最后一条用户消息。");
        return sb.toString();
    }

    /** 调用百宝箱智能体；异常一律转为明确的业务错误，不把堆栈抛给上层。 */
    private String invoke(String prompt, Map<String, Object> context, String scene) {
        if (!configured()) {
            throw new BizException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "百宝箱企业版未配置（缺少 TBOX_API_KEY / TBOX_AGENT_ID），已转本地降级通道");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentId", agentId);
            body.put("stream", false);
            body.put("temperature", temperatureFor(scene));
            body.put("prompt", prompt);
            body.put("bizContext", context == null ? Map.of() : context);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/v1/agent/invoke"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.stringify(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new BizException(ErrorCode.UPSTREAM_UNAVAILABLE,
                        "百宝箱返回 HTTP " + response.statusCode());
            }
            Map<String, Object> parsed = json.parseMap(response.body());
            Object content = parsed.getOrDefault("content", parsed.get("data"));
            return content == null ? response.body() : String.valueOf(content);
        } catch (BizException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("百宝箱调用超时: {}", e.getMessage());
            throw new BizException(ErrorCode.UPSTREAM_TIMEOUT);
        } catch (Exception e) {
            log.warn("百宝箱调用失败: {}", e.getMessage());
            throw new BizException(ErrorCode.UPSTREAM_UNAVAILABLE, "百宝箱调用失败：" + e.getMessage());
        }
    }

    /** 供前端展示当前平台配置状态（企业专项材料①的运行时证据）。 */
    public List<Map<String, Object>> describeConfig() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("key", "base-url", "value", baseUrl));
        items.add(Map.of("key", "agent-id", "value", agentId == null || agentId.isBlank() ? "(未配置)" : agentId));
        items.add(Map.of("key", "api-key", "value", apiKey == null || apiKey.isBlank() ? "(未配置)" : "******"));
        items.add(Map.of("key", "temperature.path", "value", pathTemperature));
        items.add(Map.of("key", "temperature.incentive", "value", incentiveTemperature));
        return items;
    }
}
