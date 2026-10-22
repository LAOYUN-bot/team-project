package cn.zjnu.a02.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.ai.gateway.OpenAiCompatibleGateway;

/**
 * 向量化服务：本地确定性向量 ↔ 云端 embedding 可切换。
 *
 * <p>v2.0 §3.2 推荐的 embedding 是 bge-m3（本地）或 text-embedding-v3（云）。
 * 本类默认走 {@link LocalEmbedding}（离线可用），配置
 * {@code a02.ai.embedding.provider=openai} 后切到云端；
 * **云端失败时自动回退本地**，保证 RAG 永远可用（NFR-8「降级不报错」在 RAG 侧的体现）。
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final LocalEmbedding localEmbedding;
    private final OpenAiCompatibleGateway gateway;
    private final String providerConfig;

    public EmbeddingService(LocalEmbedding localEmbedding,
                            OpenAiCompatibleGateway gateway,
                            @Value("${a02.ai.embedding.provider:local}") String providerConfig) {
        this.localEmbedding = localEmbedding;
        this.gateway = gateway;
        this.providerConfig = providerConfig;
    }

    public float[] embed(String text) {
        if ("openai".equalsIgnoreCase(providerConfig) && gateway.configured()) {
            try {
                float[] vector = gateway.embed(text);
                LocalEmbedding.normalize(vector);
                return vector;
            } catch (Exception e) {
                log.warn("云端 embedding 失败，回退本地向量：{}", e.getMessage());
            }
        }
        return localEmbedding.embed(text);
    }

    public String describe() {
        if ("openai".equalsIgnoreCase(providerConfig) && gateway.configured()) {
            return "云端 embedding（失败时自动回退本地 hashing 向量）";
        }
        return "本地确定性向量（hashing trick + TF 余弦，" + LocalEmbedding.DIMENSION + " 维，离线可用）";
    }

    public boolean isLocal() {
        return !("openai".equalsIgnoreCase(providerConfig) && gateway.configured());
    }
}
