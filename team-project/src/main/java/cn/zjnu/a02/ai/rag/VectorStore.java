package cn.zjnu.a02.ai.rag;

import java.util.List;

/**
 * 向量存储抽象（FR-7.2）。
 *
 * <p>v2.0 §3.2 建议 Milvus standalone 或 Redis Stack，但二者都需要 Docker；
 * 本项目的知识库规模只有数十个片段、且开发机离线无容器运行时，
 * 因此默认实现是 {@link JdbcVectorStore}（向量以 JSON 存在 MySQL/H2 的
 * {@code kb_chunk.embedding_json} 列，检索时在内存中做余弦相似度）。
 *
 * <p><b>迁移路径</b>：业务只依赖本接口。当片段数达到万级、或团队引入了
 * Milvus/Redis Stack 时，新增一个实现类并改配置即可，{@code RagService} 无需修改。
 */
public interface VectorStore {

    /** 一个被检索命中的片段。 */
    record ChunkHit(long chunkId, long docId, String docCode, String docTitle, int chunkNo,
                    String content, String source, String category, double score) {
    }

    /** 重建某份文档的全部片段（幂等：先删后插）。 */
    void replaceDoc(long docId, String code, List<TextChunker.Chunk> chunks,
                    List<float[]> vectors, String source, String category);

    /** 按余弦相似度返回 Top-K 片段，按分数降序。 */
    List<ChunkHit> search(float[] queryVector, int topK);

    long count();

    void clear();

    /** 实现名称，用于界面展示当前使用的向量方案。 */
    String describe();
}
