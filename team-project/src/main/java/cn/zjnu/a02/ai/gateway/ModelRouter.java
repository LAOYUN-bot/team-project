package cn.zjnu.a02.ai.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.db.Db;

/**
 * 模型分层路由（v2.0 §3.4.1「模型分层策略」）。
 *
 * <p>v2.0 的核心观点：<b>开发期 90% 的调用应该落在 Ollama 上</b>。
 * 调提示词天然需要高频重复调用，用云端 API 既费钱又撞限流。
 *
 * <table>
 *   <tr><th>tier</th><th>模型</th><th>端点</th><th>用途</th></tr>
 *   <tr><td>dev</td><td>qwen2.5:7b</td><td>Ollama 本地</td><td>开发调试，零费用无速率限制</td></tr>
 *   <tr><td>standard</td><td>deepseek-chat</td><td>云端</td><td>功能自测与联调</td></tr>
 *   <tr><td>demo</td><td>deepseek-chat（备用 qwen-plus）</td><td>云端</td><td>答辩演示</td></tr>
 * </table>
 *
 * <p>模型名与端点优先取 {@code sys_config}（可在界面上看到并调整），
 * 取不到时回退到 {@code application.yml} 的默认值。
 */
@Component
public class ModelRouter {

    private final Db db;
    private final String defaultTier;
    private final String ollamaBaseUrl;
    private final String cloudBaseUrl;
    private final String apiKey;
    private final String embeddingModel;

    public ModelRouter(Db db,
                       @Value("${a02.ai.tier:dev}") String defaultTier,
                       @Value("${a02.ai.ollama.base-url:http://127.0.0.1:11434/v1}") String ollamaBaseUrl,
                       @Value("${a02.ai.openai.base-url:https://api.deepseek.com/v1}") String cloudBaseUrl,
                       @Value("${a02.ai.openai.api-key:}") String apiKey,
                       @Value("${a02.ai.embedding.model:text-embedding-v3}") String embeddingModel) {
        this.db = db;
        this.defaultTier = defaultTier;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.cloudBaseUrl = cloudBaseUrl;
        this.apiKey = apiKey;
        this.embeddingModel = embeddingModel;
    }

    /** embedding 模型名（聚类/检索与对话模型分开配置）。 */
    public String embeddingModel() {
        return config("ai.embedding.model", embeddingModel);
    }

    public record Route(String tier, String model, String fallbackModel, String baseUrl, String apiKey) {
        public boolean usesLocalEndpoint() {
            return baseUrl != null && (baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost"));
        }
    }

    /** 当前生效路由。 */
    public Route current() {
        String tier = config("ai.provider.tier", defaultTier);
        return routeFor(tier);
    }

    public Route routeFor(String tier) {
        String normalized = tier == null ? "dev" : tier.trim().toLowerCase();
        String model;
        String baseUrl;
        switch (normalized) {
            case "standard" -> {
                model = config("ai.model.standard", "deepseek-chat");
                baseUrl = config("ai.openai.base-url", cloudBaseUrl);
            }
            case "demo" -> {
                model = config("ai.model.demo", "deepseek-chat");
                baseUrl = config("ai.openai.base-url", cloudBaseUrl);
            }
            default -> {
                normalized = "dev";
                model = config("ai.model.dev", "qwen2.5:7b");
                baseUrl = config("ai.ollama.base-url", ollamaBaseUrl);
            }
        }
        String fallback = config("ai.model.fallback", "qwen-plus");
        return new Route(normalized, model, fallback, baseUrl, apiKey);
    }

    /** 供界面展示的分层说明。 */
    public Map<String, Object> describe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tier", current().tier());
        result.put("activeModel", current().model());
        result.put("baseUrl", current().baseUrl());
        result.put("usesLocalEndpoint", current().usesLocalEndpoint());
        result.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        Map<String, String> tiers = new LinkedHashMap<>();
        tiers.put("dev（开发调试）", routeFor("dev").model() + " @ " + routeFor("dev").baseUrl());
        tiers.put("standard（功能自测）", routeFor("standard").model() + " @ " + routeFor("standard").baseUrl());
        tiers.put("demo（答辩演示）", routeFor("demo").model() + " @ " + routeFor("demo").baseUrl());
        tiers.put("fallback（连续失败时切换）", current().fallbackModel());
        result.put("tiers", tiers);
        result.put("note", "开发期建议 tier=dev 走本地 Ollama：零费用、无速率限制，适合高频调提示词");
        return result;
    }

    private String config(String key, String defaultValue) {
        try {
            Map<String, Object> row = db.one("SELECT cfg_value FROM sys_config WHERE cfg_key=?", key);
            if (row != null && row.get("cfg_value") != null) {
                String value = String.valueOf(row.get("cfg_value")).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (Exception ignored) {
            // 配置表不可用时用默认值，不应阻塞启动
        }
        return defaultValue;
    }
}
