package cn.zjnu.a02.ai.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * 基于关系库的向量存储实现（默认方案，零额外组件）。
 *
 * <p>片段向量以 JSON 数组存在 {@code kb_chunk.embedding_json}，检索时把全部片段读入内存
 * 计算余弦相似度。在当前规模（数十片段）下，一次检索耗时 <5ms，完全够用。
 *
 * <p>为什么不直接在 SQL 里算：H2/MySQL 都没有原生向量索引，
 * 硬算（如自定义函数）既不可移植也不快。内存计算 + 接口可替换是更务实的选择。
 */
@Component
public class JdbcVectorStore implements VectorStore {

    private final Db db;
    private final JsonUtils json;

    public JdbcVectorStore(Db db, JsonUtils json) {
        this.db = db;
        this.json = json;
    }

    @Override
    public void replaceDoc(long docId, String code, List<TextChunker.Chunk> chunks,
                           List<float[]> vectors, String source, String category) {
        db.update("DELETE FROM kb_chunk WHERE doc_id=?", docId);
        for (int i = 0; i < chunks.size(); i++) {
            TextChunker.Chunk chunk = chunks.get(i);
            db.insert("INSERT INTO kb_chunk(doc_id, chunk_no, content, embedding_json, char_count, source, category)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    docId, chunk.index(), chunk.content(), json.stringify(vectors.get(i)),
                    chunk.content().length(), source, category);
        }
        db.update("UPDATE kb_doc_ref SET chunk_count=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                chunks.size(), docId);
    }

    /**
     * TF-IDF 余弦检索。
     *
     * <p>为什么不能只用 TF：中文二元组的字面重叠很普遍（「的是」「一个」这类组合到处出现），
     * 纯 TF 余弦会让**不相关文档也拿到 0.2 以上的相似度**，导致拒答阈值失效。
     * 引入 IDF 后，常见组合权重被压低、罕见词（如「索引」「向量」）权重被抬高，
     * 相关与不相关的区分度显著提升——这是本项目实测出来的必要改进。
     */
    @Override
    public List<ChunkHit> search(float[] queryVector, int topK) {
        List<Map<String, Object>> rows = db.list(
                "SELECT c.id, c.doc_id, c.chunk_no, c.content, c.embedding_json, c.source, c.category,"
                        + " d.code AS doc_code, d.title AS doc_title"
                        + " FROM kb_chunk c LEFT JOIN kb_doc_ref d ON d.id = c.doc_id");
        if (rows.isEmpty()) {
            return List.of();
        }

        int dimension = LocalEmbedding.DIMENSION;
        List<float[]> vectors = new ArrayList<>(rows.size());
        List<Map<String, Object>> validRows = new ArrayList<>(rows.size());
        // ① 统计文档频率（df）
        double[] df = new double[dimension];
        for (Map<String, Object> row : rows) {
            float[] vector;
            try {
                vector = json.parse(String.valueOf(row.get("embedding_json")), float[].class);
            } catch (Exception e) {
                continue;
            }
            if (vector.length != dimension) {
                continue;
            }
            vectors.add(vector);
            validRows.add(row);
            for (int i = 0; i < dimension; i++) {
                if (Math.abs(vector[i]) > 1e-6) {
                    df[i]++;
                }
            }
        }
        int n = vectors.size();
        if (n == 0) {
            return List.of();
        }

        // ② 计算 idf，并对查询向量加权归一化
        double[] idf = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            idf[i] = Math.log((n + 1.0) / (df[i] + 1.0)) + 1.0;
        }
        float[] weightedQuery = new float[dimension];
        for (int i = 0; i < Math.min(dimension, queryVector.length); i++) {
            weightedQuery[i] = (float) (queryVector[i] * idf[i]);
        }
        LocalEmbedding.normalize(weightedQuery);

        // ③ 对每个片段同样加权归一化后计算余弦
        List<ChunkHit> hits = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            Map<String, Object> row = validRows.get(k);
            float[] vector = vectors.get(k);
            float[] weightedDoc = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                weightedDoc[i] = (float) (vector[i] * idf[i]);
            }
            LocalEmbedding.normalize(weightedDoc);
            double score = LocalEmbedding.cosine(weightedQuery, weightedDoc);

            hits.add(new ChunkHit(
                    ((Number) row.get("id")).longValue(),
                    ((Number) row.get("doc_id")).longValue(),
                    row.get("doc_code") == null ? "" : String.valueOf(row.get("doc_code")),
                    row.get("doc_title") == null ? "" : String.valueOf(row.get("doc_title")),
                    ((Number) row.get("chunk_no")).intValue(),
                    String.valueOf(row.get("content")),
                    String.valueOf(row.get("source")),
                    row.get("category") == null ? "" : String.valueOf(row.get("category")),
                    Math.round(score * 10000) / 10000.0));
        }
        hits.sort(Comparator.comparingDouble(ChunkHit::score).reversed());
        return hits.size() <= topK ? hits : hits.subList(0, topK);
    }

    @Override
    public long count() {
        return db.count("SELECT COUNT(*) FROM kb_chunk");
    }

    @Override
    public void clear() {
        db.update("DELETE FROM kb_chunk");
        db.update("UPDATE kb_doc_ref SET chunk_count=0");
    }

    @Override
    public String describe() {
        return "JdbcVectorStore（向量存于 kb_chunk.embedding_json，内存余弦检索）";
    }
}
