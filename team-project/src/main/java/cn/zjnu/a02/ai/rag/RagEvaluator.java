package cn.zjnu.a02.ai.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.common.db.Db;

/**
 * RAG 效果评估（FR-7.2，v2.0 §3.3.2「效果评估」与难点 D-7）。
 *
 * <p>在 52 条构造问答对上计算三类指标：
 * <ul>
 *   <li><b>Recall@K</b>：Top-K 命中片段中是否包含「应命中的文档」——衡量召回；</li>
 *   <li><b>关键词命中率</b>：命中片段是否包含期望的关键事实词——衡量「答得准」；</li>
 *   <li><b>拒答率</b>：低于阈值的比例——衡量防幻觉策略是否过严/过松。</li>
 * </ul>
 *
 * <p>这是「AI 功能可用性」这一课程质量门槛的直接证据，也是答辩最有说服力的材料之一。
 */
@Service
public class RagEvaluator {

    private final Db db;
    private final RagService ragService;
    private final double minScore;

    public RagEvaluator(Db db, RagService ragService,
                        @Value("${a02.rag.min-score:0.12}") double minScore) {
        this.db = db;
        this.ragService = ragService;
        this.minScore = minScore;
    }

    public record CaseResult(String question, String expectedDoc, boolean docHit,
                             boolean keywordHit, double topScore, boolean refused,
                             List<String> retrievedDocs) {
    }

    public record EvalReport(int total, int k, double recallAtK, double recallAt1, double mrr,
                             double keywordHitRate, double refusalRate, double avgTopScore,
                             int failures, List<CaseResult> cases,
                             List<Map<String, Object>> failureDetails, String conclusion) {
    }

    public EvalReport evaluate(int k) {
        int topK = Math.max(1, Math.min(20, k));
        List<Map<String, Object>> rows = db.list(
                "SELECT question, expected_doc_code, expected_keywords FROM kb_eval_case ORDER BY id");

        List<CaseResult> results = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        int docHits = 0;
        int top1Hits = 0;
        double reciprocalRankSum = 0;
        int keywordHits = 0;
        int refusals = 0;
        double scoreSum = 0;

        for (Map<String, Object> row : rows) {
            String question = String.valueOf(row.get("question"));
            String expectedDoc = row.get("expected_doc_code") == null
                    ? "" : String.valueOf(row.get("expected_doc_code"));
            String keywords = row.get("expected_keywords") == null
                    ? "" : String.valueOf(row.get("expected_keywords"));

            List<RagService.SearchHit> hits = ragService.searchForEval(question, topK);
            double topScore = hits.isEmpty() ? 0 : hits.get(0).score();
            scoreSum += topScore;
            boolean refused = hits.isEmpty() || topScore < minScore;
            if (refused) {
                refusals++;
            }

            List<String> retrievedDocs = hits.stream().map(RagService.SearchHit::docCode).distinct().toList();
            boolean docHit = !expectedDoc.isBlank() && retrievedDocs.contains(expectedDoc);
            if (docHit) {
                docHits++;
            }
            // 更严格的指标：Top-1 命中率与 MRR（避免「只有 6 篇文档、Top-5 必然命中」造成的虚高）
            if (!expectedDoc.isBlank() && !hits.isEmpty()
                    && expectedDoc.equals(hits.get(0).docCode())) {
                top1Hits++;
                reciprocalRankSum += 1.0;
            } else if (!expectedDoc.isBlank()) {
                for (int rank = 1; rank <= hits.size(); rank++) {
                    if (expectedDoc.equals(hits.get(rank - 1).docCode())) {
                        reciprocalRankSum += 1.0 / rank;
                        break;
                    }
                }
            }

            boolean keywordHit = false;
            for (RagService.SearchHit hit : hits) {
                if (containsKeywords(hit.content(), keywords)) {
                    keywordHit = true;
                    break;
                }
            }
            if (keywordHit) {
                keywordHits++;
            }

            CaseResult result = new CaseResult(question, expectedDoc, docHit, keywordHit,
                    topScore, refused, retrievedDocs);
            results.add(result);

            if (!docHit || !keywordHit) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("question", question);
                detail.put("expectedDoc", expectedDoc);
                detail.put("docHit", docHit);
                detail.put("keywordHit", keywordHit);
                detail.put("topScore", topScore);
                detail.put("retrievedDocs", retrievedDocs);
                detail.put("keywords", keywords);
                failures.add(detail);
            }
        }

        int total = rows.size();
        double recall = total == 0 ? 0 : round(docHits * 100.0 / total);
        double recallAt1 = total == 0 ? 0 : round(top1Hits * 100.0 / total);
        double mrr = total == 0 ? 0 : round(reciprocalRankSum / total * 1000) / 1000.0;
        double keywordRate = total == 0 ? 0 : round(keywordHits * 100.0 / total);
        double refusalRate = total == 0 ? 0 : round(refusals * 100.0 / total);
        double avgScore = total == 0 ? 0 : round(scoreSum / total);

        String conclusion = "Recall@" + topK + " = " + recall + "%，Recall@1 = " + recallAt1
                + "%，MRR = " + mrr + "，关键词命中率 = " + keywordRate
                + "%，拒答率 = " + refusalRate + "%（共 " + total + " 条评测）"
                + (recall >= 80 ? "；达到 v2.0 的 Recall@5 ≥80% 目标。" : "；低于 80% 目标，需调优切块或阈值。");

        return new EvalReport(total, topK, recall, recallAt1, mrr, keywordRate, refusalRate,
                avgScore, failures.size(), results, failures, conclusion);
    }

    private static boolean containsKeywords(String content, String keywords) {
        if (keywords == null || keywords.isBlank()) {
            return true;
        }
        int matched = 0;
        String[] parts = keywords.split("\\|");
        for (String part : parts) {
            String keyword = part.strip();
            if (keyword.isEmpty()) {
                continue;
            }
            if (content.contains(keyword)) {
                matched++;
            }
        }
        // 命中任一关键事实词即认为「切题」，避免要求一条片段包含所有词而过于苛刻
        return matched > 0;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
