package cn.zjnu.a02.ai.gateway;

import java.util.List;
import java.util.function.Consumer;

/**
 * 统一模型网关（FR-7.1，v2.0 §3.3.3）。
 *
 * <p><b>设计约束</b>：所有模型调用必须经过本接口，业务代码**不允许**直接调厂商 SDK 或拼 HTTP。
 * 这是同时解决「可切换」「成本可控」「限流防护」三件事的唯一干净做法。
 *
 * <p>厂商差异由适配器吸收：DeepSeek / 通义千问 / 智谱 / Ollama 都兼容 OpenAI 协议，
 * 因此只需要一个 {@link OpenAiCompatibleGateway}，靠不同的 {@code base-url + model} 区分。
 */
public interface LlmGateway {

    record ChatMessage(String role, String content) {
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }

        public static ChatMessage assistant(String content) {
            return new ChatMessage("assistant", content);
        }
    }

    /**
     * 一次对话请求。
     *
     * @param scene       业务场景（path/unit/score/rag/persona...），用于路由与成本统计
     * @param userId      调用者，用于按用户限流
     * @param messages    对话消息
     * @param temperature 温度（按场景分离：路径 0.3 / 激励 0.8）
     * @param maxTokens   输出上限（控制成本）
     */
    record ChatRequest(String scene, Long userId, List<ChatMessage> messages,
                       double temperature, int maxTokens) {
    }

    /**
     * 一次对话结果。
     *
     * @param promptTokens     输入 token（供应商返回值，缺失时用估算）
     * @param completionTokens 输出 token
     */
    record ChatResponse(String content, String provider, String model,
                        int promptTokens, int completionTokens, long latencyMs) {
    }

    ChatResponse chat(ChatRequest request);

    void chatStream(ChatRequest request, Consumer<String> onChunk);

    /** 文本向量化（RAG 使用）。 */
    float[] embed(String text);

    String provider();

    /** 是否已具备真实调用条件（配置齐全）。 */
    boolean configured();

    /** 当前生效的模型名与端点，用于界面展示。 */
    String describeModel();
}
