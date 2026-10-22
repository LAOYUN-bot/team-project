package cn.zjnu.a02.ai.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.ai.gateway.LlmGateway;
import cn.zjnu.a02.ai.gateway.ModelRouter;
import cn.zjnu.a02.ai.prompt.PromptLoader;
import cn.zjnu.a02.common.port.AiChatPort;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * OpenAI 兼容协议的模型适配器（FR-7.1，v2.0 取代原百宝箱适配器）。
 *
 * <p>通过 {@link LlmGateway} 访问 DeepSeek / 通义千问 / 智谱 / Ollama——
 * 它们都实现了 OpenAI 的 {@code /chat/completions} 协议，差异只在
 * {@code base-url + model}（由 {@link ModelRouter} 按 tier 决定）。
 *
 * <p><b>没有 API Key 也能构建应用</b>：本适配器只在 {@code a02.ai.provider=openai}
 * 且网关已配置时被调用；否则由 AiService 路由到 Mock 适配器。
 */
@Component
public class OpenAiCompatibleAiAdapter extends AbstractStructuredAiAdapter {

    private final LlmGateway gateway;
    private final ModelRouter modelRouter;
    private final double pathTemperature;
    private final double incentiveTemperature;

    public OpenAiCompatibleAiAdapter(PromptLoader prompts,
                                     JsonUtils json,
                                     @Value("${a02.ai.max-retry:2}") int maxRetry,
                                     LlmGateway gateway,
                                     ModelRouter modelRouter,
                                     @Value("${a02.ai.temperature.path:0.3}") double pathTemperature,
                                     @Value("${a02.ai.temperature.incentive:0.8}") double incentiveTemperature) {
        super(prompts, json, maxRetry);
        this.gateway = gateway;
        this.modelRouter = modelRouter;
        this.pathTemperature = pathTemperature;
        this.incentiveTemperature = incentiveTemperature;
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    protected String modelName() {
        return modelRouter.current().model();
    }

    public boolean configured() {
        return gateway.configured();
    }

    /** 温度按场景分离：路径求稳 0.3、激励与对话求多样 0.8。 */
    private double temperatureFor(String scene) {
        return ("incentive".equals(scene) || "persona".equals(scene) || "coach".equals(scene))
                ? incentiveTemperature : pathTemperature;
    }

    @Override
    protected String rawStructured(StructuredRequest request, String repairHint) {
        StringBuilder prompt = new StringBuilder();
        if (request.taskPrompt() != null) {
            prompt.append(request.taskPrompt()).append("\n\n");
        }
        prompt.append("【业务上下文（已脱敏）】\n")
                .append(json.stringify(request.context() == null ? Map.of() : request.context()))
                .append("\n\n");
        if (repairHint != null) {
            // 把上一轮校验错误回灌给模型，否则重试没有意义（v2.0 §3.3.1）
            prompt.append("【上一次输出未通过校验，原因：").append(repairHint)
                    .append("。请严格按要求的 JSON 格式重新输出，不要包含解释文字。】\n");
        }
        prompt.append("请只输出 JSON，不要输出代码块围栏或任何解释。");

        List<LlmGateway.ChatMessage> messages = new ArrayList<>();
        messages.add(LlmGateway.ChatMessage.system(nullToEmpty(request.systemPrompt())));
        messages.add(LlmGateway.ChatMessage.user(prompt.toString()));

        return gateway.chat(new LlmGateway.ChatRequest(
                request.scene(), request.userId(), messages, temperatureFor(request.scene()), 2000)).content();
    }

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        LlmGateway.ChatResponse response = gateway.chat(new LlmGateway.ChatRequest(
                request.scene(), request.userId(), toMessages(request), temperatureFor(request.scene()), 1200));
        return new ChatResult(response.content(), provider(), response.model(),
                System.currentTimeMillis() - start, false);
    }

    @Override
    public void stream(ChatRequest request, Consumer<String> onChunk) {
        gateway.chatStream(new LlmGateway.ChatRequest(
                request.scene(), request.userId(), toMessages(request), temperatureFor(request.scene()), 1200),
                onChunk);
    }

    private List<LlmGateway.ChatMessage> toMessages(ChatRequest request) {
        List<LlmGateway.ChatMessage> messages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(LlmGateway.ChatMessage.system(request.systemPrompt()));
        }
        if (request.messages() != null) {
            for (AiChatPort.Message message : request.messages()) {
                messages.add(new LlmGateway.ChatMessage(message.role(), message.content()));
            }
        }
        return messages;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
