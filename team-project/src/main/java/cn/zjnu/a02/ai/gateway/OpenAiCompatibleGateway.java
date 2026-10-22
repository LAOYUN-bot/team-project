package cn.zjnu.a02.ai.gateway;

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
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.util.JsonUtils;

/**
 * OpenAI 兼容协议网关适配器（FR-7.1）。
 *
 * <p>DeepSeek、通义千问（兼容模式）、智谱、Ollama 都实现了 OpenAI 的
 * {@code /chat/completions} 与 {@code /embeddings} 接口，因此**一个适配器即可覆盖多厂商**，
 * 差异只体现在 {@code base-url + model}（由 {@link ModelRouter} 决定）。
 *
 * <p>这是 v2.0「换模型只改配置不改业务代码」的具体实现。
 */
@Component
public class OpenAiCompatibleGateway implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleGateway.class);

    private final ModelRouter modelRouter;
    private final JsonUtils json;
    private final HttpClient httpClient;

    public OpenAiCompatibleGateway(ModelRouter modelRouter, JsonUtils json) {
        this.modelRouter = modelRouter;
        this.json = json;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String provider() {
        return "openai-compatible";
    }

    @Override
    public boolean configured() {
        ModelRouter.Route route = modelRouter.current();
        // 本地端点（Ollama）不需要 API Key；云端端点必须有 Key 才认为已配置
        return route.usesLocalEndpoint() || (route.apiKey() != null && !route.apiKey().isBlank());
    }

    @Override
    public String describeModel() {
        ModelRouter.Route route = modelRouter.current();
        return route.model() + " @ " + route.baseUrl();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ModelRouter.Route route = requireConfigured();
        long start = System.currentTimeMillis();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.model());
        body.put("messages", toMessages(request.messages()));
        body.put("temperature", request.temperature());
        body.put("stream", false);
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }

        HttpResponse<String> response = post(route, "/chat/completions", body);
        Map<String, Object> parsed = json.parseMap(response.body());
        String content = extractContent(parsed);
        int[] usage = extractUsage(parsed);
        return new ChatResponse(content, provider(), route.model(), usage[0], usage[1],
                System.currentTimeMillis() - start);
    }

    @Override
    public void chatStream(ChatRequest request, Consumer<String> onChunk) {
        ModelRouter.Route route = requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", route.model());
        body.put("messages", toMessages(request.messages()));
        body.put("temperature", request.temperature());
        body.put("stream", true);
        if (request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }

        HttpRequest httpRequest = baseRequest(route, "/chat/completions", body).build();
        try {
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() >= 400) {
                throw mapHttpError(response.statusCode(), "流式调用失败");
            }
            response.body().forEach(line -> {
                if (!line.startsWith("data:")) {
                    return;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    return;
                }
                try {
                    String delta = extractDelta(json.parseMap(data));
                    if (delta != null && !delta.isEmpty()) {
                        onChunk.accept(delta);
                    }
                } catch (Exception e) {
                    log.debug("跳过无法解析的流式分片: {}", data);
                }
            });
        } catch (LlmException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException(LlmException.Code.TIMEOUT, "模型流式响应超时");
        } catch (Exception e) {
            throw new LlmException(LlmException.Code.UPSTREAM_UNAVAILABLE,
                    "模型流式调用失败：" + e.getMessage(), e);
        }
    }

    @Override
    public float[] embed(String text) {
        ModelRouter.Route route = requireConfigured();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelRouter.embeddingModel());
        body.put("input", text);

        HttpResponse<String> response = post(route, "/embeddings", body);
        Map<String, Object> parsed = json.parseMap(response.body());
        Object data = parsed.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty()) {
            throw new LlmException(LlmException.Code.INVALID_RESPONSE, "embedding 响应缺少 data");
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> map) || !(map.get("embedding") instanceof List<?> values)) {
            throw new LlmException(LlmException.Code.INVALID_RESPONSE, "embedding 响应缺少向量");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = ((Number) values.get(i)).floatValue();
        }
        return vector;
    }

    // ------------------------------------------------------------------
    // HTTP 与协议解析
    // ------------------------------------------------------------------

    private ModelRouter.Route requireConfigured() {
        ModelRouter.Route route = modelRouter.current();
        if (!route.usesLocalEndpoint() && (route.apiKey() == null || route.apiKey().isBlank())) {
            throw new LlmException(LlmException.Code.NOT_CONFIGURED,
                    "云端模型未配置 API Key（a02.ai.openai.api-key 或环境变量 OPENAI_API_KEY）");
        }
        return route;
    }

    private HttpRequest.Builder baseRequest(ModelRouter.Route route, String path, Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(stripTrailingSlash(route.baseUrl()) + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.stringify(body)));
        if (route.apiKey() != null && !route.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + route.apiKey());
        }
        return builder;
    }

    private HttpResponse<String> post(ModelRouter.Route route, String path, Map<String, Object> body) {
        try {
            HttpResponse<String> response = httpClient.send(
                    baseRequest(route, path, body).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw mapHttpError(response.statusCode(), abbreviate(response.body()));
            }
            return response;
        } catch (LlmException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmException(LlmException.Code.TIMEOUT, "模型响应超时（60s）");
        } catch (Exception e) {
            throw new LlmException(LlmException.Code.UPSTREAM_UNAVAILABLE,
                    "模型端点不可用：" + e.getMessage(), e);
        }
    }

    /**
     * 错误码映射（v2.0 §3.4.2 要求把限流单列）：
     * 429 → RATE_LIMITED（退避，不即时重试）；5xx/连接失败 → UPSTREAM_UNAVAILABLE；其余 → INVALID_RESPONSE。
     */
    private LlmException mapHttpError(int status, String body) {
        if (status == 429) {
            return new LlmException(LlmException.Code.RATE_LIMITED,
                    "模型供应商限流（429）。按 NFR-8 应指数退避，禁止即时重试。响应：" + body);
        }
        if (status >= 500) {
            return new LlmException(LlmException.Code.UPSTREAM_UNAVAILABLE,
                    "模型服务 " + status + "：" + body);
        }
        if (status == 401 || status == 403) {
            return new LlmException(LlmException.Code.NOT_CONFIGURED,
                    "模型鉴权失败（" + status + "），请检查 API Key：" + body);
        }
        return new LlmException(LlmException.Code.INVALID_RESPONSE,
                "模型返回 " + status + "：" + body);
    }

    private List<Map<String, String>> toMessages(List<ChatMessage> messages) {
        List<Map<String, String>> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            result.add(Map.of("role", message.role(), "content", message.content()));
        }
        return result;
    }

    private String extractContent(Map<String, Object> response) {
        if (response.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") != null) {
            return String.valueOf(message.get("content"));
        }
        throw new LlmException(LlmException.Code.INVALID_RESPONSE, "响应缺少 choices[0].message.content");
    }

    private String extractDelta(Map<String, Object> chunk) {
        if (chunk.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.get(0) instanceof Map<?, ?> choice
                && choice.get("delta") instanceof Map<?, ?> delta
                && delta.get("content") != null) {
            return String.valueOf(delta.get("content"));
        }
        return null;
    }

    private int[] extractUsage(Map<String, Object> response) {
        if (response.get("usage") instanceof Map<?, ?> usage) {
            int prompt = usage.get("prompt_tokens") instanceof Number n ? n.intValue() : 0;
            int completion = usage.get("completion_tokens") instanceof Number n ? n.intValue() : 0;
            return new int[]{prompt, completion};
        }
        return new int[]{0, 0};
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
