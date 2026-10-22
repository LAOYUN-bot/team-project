package cn.zjnu.a02.m7integration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.ai.rag.RagEvaluator;
import cn.zjnu.a02.ai.rag.RagService;
import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * M7 集成与合规层 —— 自建 RAG 知识库接口（FR-7.2，v2.0 新增）。
 *
 * <p>对应 v2.0 §3.5 的 {@code POST /api/kb/search}，并扩展出问答与评估：
 * <ul>
 *   <li>{@code POST /api/kb/search}：纯检索，返回片段与相关度（用于观察召回质量）；</li>
 *   <li>{@code POST /api/kb/ask}：RAG 问答，**上下文不足时拒答**，答案带来源引用；</li>
 *   <li>{@code POST /api/kb/eval}：跑评测集，输出 Recall@K / 关键词命中率 / 拒答率；</li>
 *   <li>{@code POST /api/kb/ingest}：重建知识库片段（幂等）；</li>
 *   <li>{@code GET /api/kb/stats}：知识库运行态统计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeBaseController {

    private final RagService ragService;
    private final RagEvaluator evaluator;

    public KnowledgeBaseController(RagService ragService, RagEvaluator evaluator) {
        this.ragService = ragService;
        this.evaluator = evaluator;
    }

    @PostMapping("/search")
    public ApiResponse<Map<String, Object>> search(@RequestBody SearchRequest request) {
        List<RagService.SearchHit> hits = ragService.search(
                AuthContext.userId(), request.query(), request.topK());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", request.query());
        result.put("hitCount", hits.size());
        result.put("hits", hits.stream().map(hit -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunkId", hit.chunkId());
            item.put("docCode", hit.docCode());
            item.put("docTitle", hit.docTitle());
            item.put("chunkNo", hit.chunkNo());
            item.put("score", hit.score());
            item.put("source", hit.source());
            item.put("category", hit.category());
            item.put("content", hit.content());
            return item;
        }).toList());
        result.put("note", "score 为余弦相似度（0-1）；低于阈值的片段会被拒答策略过滤。");
        return ApiResponse.ok(result);
    }

    /** RAG 问答：上下文不足时拒答，答案带来源。 */
    @PostMapping("/ask")
    public ApiResponse<Map<String, Object>> ask(@RequestBody AskRequest request) {
        RagService.AskResult answer = ragService.ask(AuthContext.userId(), request.question());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", request.question());
        result.put("refused", answer.refused());
        result.put("answer", answer.answer());
        result.put("answerer", answer.answerer());
        result.put("citations", answer.citations());
        result.put("topScore", answer.topScore());
        result.put("coverage", answer.coverage());
        result.put("hitCount", answer.hitCount());
        result.put("latencyMs", answer.latencyMs());
        result.put("retriever", answer.retriever());
        result.put("note", answer.note());
        result.put("disclaimer", "以上回答基于本系统知识库检索结果，AI 生成内容仅供参考，请核对引用来源。");
        return ApiResponse.ok(result);
    }

    /** RAG 效果评估：Recall@K / 关键词命中率 / 拒答率。 */
    @PostMapping("/eval")
    public ApiResponse<RagEvaluator.EvalReport> eval(@RequestBody(required = false) EvalRequest request) {
        int k = request == null || request.k() == null ? 5 : request.k();
        return ApiResponse.ok(evaluator.evaluate(k));
    }

    /** 重建知识库（切块 + 向量化 + 入库，幂等）。 */
    @PostMapping("/ingest")
    public ApiResponse<RagService.IngestReport> ingest() {
        return ApiResponse.ok(ragService.ingest());
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(ragService.stats());
    }

    public record SearchRequest(String query, Integer topK) {
    }

    public record AskRequest(String question) {
    }

    public record EvalRequest(Integer k) {
    }
}
