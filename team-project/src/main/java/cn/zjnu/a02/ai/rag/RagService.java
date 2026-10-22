package cn.zjnu.a02.ai.rag;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;

/**
 * 自建 RAG 管线（FR-7.2，v2.0 §3.3.2）。
 *
 * <p>完整链路：
 * <pre>
 *   语料(classpath:kb/*.md) → 语义切块 → 向量化 → 存入向量库(kb_chunk)
 *                                                          ↓
 *   用户提问 → 向量化 → Top-K 检索 → 相关度门槛判定 → 拼装上下文 → 生成回答
 *                                                          ↓
 *   拒答（低于门槛） 或 「带来源引用的回答」
 * </pre>
 *
 * <p><b>防幻觉的两个硬手段</b>：
 * <ol>
 *   <li><b>拒答机制</b>：最高相关度低于 {@code rag.min-score} 时直接回答
 *       「知识库中暂无相关信息」，而不是让模型自由发挥；</li>
 *   <li><b>可追溯</b>：答案必须附带引用片段（来源 + 原文摘录 + 相关度分数），
 *       用户可逐条核对。</li>
 * </ol>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final Db db;
    private final TextChunker chunker;
    private final EmbeddingService embeddingService;
    private final LocalEmbedding localEmbedding;
    private final VectorStore vectorStore;
    private final int topK;
    private final double minScore;
    private final double minCoverage;
    private final boolean autoIngest;

    public RagService(Db db,
                      TextChunker chunker,
                      EmbeddingService embeddingService,
                      LocalEmbedding localEmbedding,
                      VectorStore vectorStore,
                      @Value("${a02.rag.top-k:5}") int topK,
                      @Value("${a02.rag.min-score:0.12}") double minScore,
                      @Value("${a02.rag.min-coverage:0.3}") double minCoverage,
                      @Value("${a02.rag.auto-ingest:true}") boolean autoIngest) {
        this.db = db;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.localEmbedding = localEmbedding;
        this.vectorStore = vectorStore;
        this.topK = Math.max(1, topK);
        this.minScore = minScore;
        this.minCoverage = minCoverage;
        this.autoIngest = autoIngest;
    }

    // ------------------------------------------------------------------
    // 数据模型
    // ------------------------------------------------------------------

    public record SearchHit(long chunkId, long docId, String docCode, String docTitle, int chunkNo,
                            String content, String source, String category, double score) {
    }

    public record Citation(String title, String source, String category, double score, String excerpt) {
    }

    /**
     * RAG 回答结果。
     *
     * @param refused    是否因上下文不足而拒答
     * @param answerer   回答生成方式：extractive（抽取式，离线）/ llm（真实模型）
     * @param coverage   问题关键词在检索片段中的覆盖率（拒答的第二判据）
     */
    public record AskResult(boolean refused, String answer, String answerer,
                            List<Citation> citations, double topScore, double coverage, int hitCount,
                            long latencyMs, String retriever, String note) {
    }

    public record IngestReport(int docs, int chunks, long elapsedMs, List<String> details) {
    }

    // ------------------------------------------------------------------
    // 摄取（启动时自动执行）
    // ------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        if (!autoIngest) {
            return;
        }
        try {
            long existing = vectorStore.count();
            if (existing > 0) {
                log.info("知识库已有 {} 个片段，跳过重新摄取", existing);
                return;
            }
            IngestReport report = ingest();
            log.info("RAG 知识库摄取完成：{} 份文档，{} 个片段，耗时 {}ms",
                    report.docs(), report.chunks(), report.elapsedMs());
        } catch (Exception e) {
            log.warn("知识库摄取失败（RAG 功能将不可用，但不影响其他功能）：{}", e.getMessage(), e);
        }
    }

    /** 重建全部知识库片段（幂等）。 */
    @Transactional
    public IngestReport ingest() {
        long start = System.currentTimeMillis();
        List<Map<String, Object>> docs = db.list(
                "SELECT id, code, title, source, category FROM kb_doc_ref WHERE code IS NOT NULL ORDER BY id");
        int totalChunks = 0;
        int processedDocs = 0;
        List<String> details = new ArrayList<>();

        for (Map<String, Object> doc : docs) {
            String code = String.valueOf(doc.get("code"));
            String text = readCorpus(code);
            if (text == null || text.isBlank()) {
                details.add(code + "：语料缺失，已跳过");
                continue;
            }
            List<TextChunker.Chunk> chunks = chunker.split(text);
            if (chunks.isEmpty()) {
                details.add(code + "：切块结果为空，已跳过");
                continue;
            }
            List<float[]> vectors = new ArrayList<>(chunks.size());
            String docTitle = String.valueOf(doc.get("title"));
            for (TextChunker.Chunk chunk : chunks) {
                // 向量文本 = 文档标题 + 正文（剔除标题行）。
                // 拼接标题是关键：同属一篇文档的所有片段共享「Java 后端」这类文档级语义，
                // 否则只含「MySQL/SQL」的片段会输给其他文档里泛泛提到「数据」的片段。
                // 同时剔除片段内的标题行——它们与问题字面高度重合却不含答案。
                vectors.add(embeddingService.embed(docTitle + "\n" + bodyForEmbedding(chunk.content())));
            }
            vectorStore.replaceDoc(((Number) doc.get("id")).longValue(), code, chunks, vectors,
                    String.valueOf(doc.get("source")),
                    doc.get("category") == null ? "" : String.valueOf(doc.get("category")));
            totalChunks += chunks.size();
            processedDocs++;
            details.add(code + "：" + chunks.size() + " 个片段");
        }
        return new IngestReport(processedDocs, totalChunks,
                System.currentTimeMillis() - start, details);
    }

    private String readCorpus(String code) {
        try (InputStream in = new ClassPathResource("kb/" + code + ".md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉 markdown 标题行后的正文，用于计算向量。 */
    static String bodyForEmbedding(String content) {
        StringBuilder body = new StringBuilder();
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            body.append(trimmed).append('\n');
        }
        return body.toString();
    }

    // ------------------------------------------------------------------
    // 检索
    // ------------------------------------------------------------------

    /** 纯检索（不生成回答），用于界面展示召回效果与评测。 */
    public List<SearchHit> search(Long userId, String query, Integer limit) {
        if (query == null || query.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "检索内容不能为空");
        }
        int k = limit == null ? topK : Math.max(1, Math.min(20, limit));
        long start = System.currentTimeMillis();
        List<SearchHit> hits = doSearch(query, k);

        try {
            db.insert("INSERT INTO kb_retrieval_log(user_id, query, top_score, hit_count, refused, latency_ms)"
                            + " VALUES (?,?,?,?,?,?)",
                    userId, query.length() > 500 ? query.substring(0, 500) : query,
                    hits.isEmpty() ? 0 : hits.get(0).score(), hits.size(),
                    hits.isEmpty() || hits.get(0).score() < minScore ? 1 : 0,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.debug("检索日志写入失败：{}", e.getMessage());
        }
        return hits;
    }

    /** 供评估使用：不写检索日志，避免污染线上统计。 */
    public List<SearchHit> searchForEval(String query, int limit) {
        return doSearch(query, limit);
    }

    private List<SearchHit> doSearch(String query, int k) {
        float[] queryVector = embeddingService.embed(query);
        List<VectorStore.ChunkHit> raw = vectorStore.search(queryVector, k);
        List<SearchHit> hits = new ArrayList<>(raw.size());
        for (VectorStore.ChunkHit hit : raw) {
            hits.add(new SearchHit(hit.chunkId(), hit.docId(), hit.docCode(), hit.docTitle(),
                    hit.chunkNo(), hit.content(), hit.source(), hit.category(), hit.score()));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // RAG 问答
    // ------------------------------------------------------------------

    /**
     * 带来源引用的问答。上下文不足时**拒答**（防幻觉）。
     *
     * <p>回答生成分两条路：
     * <ul>
     *   <li>离线/无模型 Key：<b>抽取式回答</b>——直接从命中片段中摘取与问题最相关的句子，
     *       不经模型改写，因此不可能编造内容；</li>
     *   <li>配置了真实模型：把检索片段作为上下文交给模型生成，并要求标注引用来源。</li>
     * </ul>
     */
    public AskResult ask(Long userId, String question) {
        long start = System.currentTimeMillis();
        List<SearchHit> hits = search(userId, question, topK);
        double topScore = hits.isEmpty() ? 0 : hits.get(0).score();
        double coverage = coverage(question, hits);

        // ① 拒答判据（双信号）：
        //    · 余弦相关度：整体语义是否靠近知识库
        //    · 关键词覆盖率：问题的实词是否真的出现在片段里
        // 只用余弦会出现「完全不相关的问题也拿到 0.24」的情况（中文二元组普遍重叠），
        // 加上覆盖率后，跑题问题会因为实词一个都没出现而被拒答。
        boolean lowScore = hits.isEmpty() || topScore < minScore;
        boolean lowCoverage = coverage < minCoverage;
        if (lowScore || lowCoverage) {
            String reason = lowScore
                    ? "最高相关度 " + topScore + " 低于阈值 " + minScore
                    : "问题关键词覆盖率 " + round2(coverage) + " 低于阈值 " + minCoverage;
            return new AskResult(true,
                    "知识库中暂无相关信息。",
                    "refusal",
                    List.of(),
                    topScore, round2(coverage), hits.size(),
                    System.currentTimeMillis() - start,
                    vectorStore.describe(),
                    "拒答依据：" + reason + "。系统按防幻觉策略拒答，建议换一个更具体的问法，或补充知识库资料。");
        }

        List<Citation> citations = buildCitations(hits);
        String answer = extractiveAnswer(question, hits);
        return new AskResult(false, answer, "extractive", citations, topScore, round2(coverage),
                hits.size(), System.currentTimeMillis() - start, vectorStore.describe(),
                "回答由检索片段**直接摘录**生成（未经模型改写），可逐条核对引用来源。");
    }

    /**
     * 关键词覆盖率：问题中的实词（长度 ≥2、非泛词）有多大比例出现在 Top-3 片段里。
     * 这是「跑题问题」最有效的判据。
     */
    private double coverage(String question, List<SearchHit> hits) {
        List<String> tokens = contentTokens(question);
        if (tokens.isEmpty() || hits.isEmpty()) {
            return 0;
        }
        StringBuilder corpus = new StringBuilder();
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            corpus.append(hits.get(i).content()).append('\n');
        }
        String text = corpus.toString();
        int matched = 0;
        for (String token : tokens) {
            if (text.contains(token)) {
                matched++;
            }
        }
        return (double) matched / tokens.size();
    }

    /** 抽取问题中的实词：去掉泛词后保留长度 ≥2 的 token。 */
    private List<String> contentTokens(String question) {
        List<String> tokens = new ArrayList<>();
        for (String token : localEmbedding.tokenize(question).keySet()) {
            if (token.length() >= 2 && !GENERIC_TOKENS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** 泛词表：这些词在任何职业类文档里都高频出现，区分度低，不应作为判据。 */
    private static final java.util.Set<String> GENERIC_TOKENS = java.util.Set.of(
            "岗位", "技能", "要求", "需要", "掌握", "哪些", "相关", "能力", "工作", "职责",
            "什么", "怎么", "如何", "可以", "是否", "应该", "主要", "一般", "通常",
            "我们", "你们", "这个", "那个", "请问", "告诉", "了解", "知道", "介绍");

    private List<Citation> buildCitations(List<SearchHit> hits) {
        List<Citation> citations = new ArrayList<>();
        for (SearchHit hit : hits) {
            if (hit.score() < minScore) {
                continue;
            }
            citations.add(new Citation(hit.docTitle(), hit.source(), hit.category(), hit.score(),
                    excerpt(hit.content(), 160)));
        }
        return citations;
    }

    /**
     * 抽取式回答：从命中片段中挑选与问题最相关的**正文句子**原样摘出。
     *
     * <p>三个要点（都是实测调出来的）：
     * <ol>
     *   <li><b>跳过标题行</b>：`## 一、岗位职责` 与问题字面高度重合，但完全不含答案，
     *       不排除就会把标题当成答案输出；</li>
     *   <li><b>按词的区分度加权</b>：命中「索引」「窗口函数」这类长词远比命中「岗位」有价值；</li>
     *   <li><b>按原文顺序输出</b>：拼接后仍然读得通，而不是把高分句随机罗列。</li>
     * </ol>
     */
    private String extractiveAnswer(String question, List<SearchHit> hits) {
        List<String> tokens = contentTokens(question);

        record Candidate(int hitIndex, int order, String text, int score, int distinct) {
        }
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;

        for (int h = 0; h < hits.size(); h++) {
            SearchHit hit = hits.get(h);
            if (hit.score() < minScore) {
                continue;
            }
            for (String raw : splitSentences(hit.content())) {
                order++;
                // 标题行（# / ## 开头）在清洗前就要跳过，否则会变成「答案」
                String rawTrimmed = raw.strip();
                if (rawTrimmed.startsWith("#") || rawTrimmed.length() < 10) {
                    continue;
                }
                String sentence = cleanSentence(raw);
                if (sentence.length() < 10 || looksLikeHeading(sentence)) {
                    continue;
                }
                int score = 0;
                int distinct = 0;
                for (String token : tokens) {
                    if (!sentence.contains(token)) {
                        continue;
                    }
                    distinct++;
                    // 英文/数字词与长词更具体，权重更高
                    boolean latin = token.chars().allMatch(c -> c < 0x4E00 || c > 0x9FFF);
                    score += latin ? 6 : token.length();
                }
                if (distinct > 0) {
                    candidates.add(new Candidate(h, order, sentence, score, distinct));
                }
            }
        }

        // 先要求「命中 ≥2 个不同实词」，不足再放宽到 1 个，避免被单个泛词带偏
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> b.distinct() != a.distinct()
                ? Integer.compare(b.distinct(), a.distinct())
                : Integer.compare(b.score(), a.score()));

        List<String> picked = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        // 先只接受「命中 ≥2 个不同实词」的句子；一句都没找到时才放宽到 1 个，
        // 避免为了凑满 3 句而把只沾一个泛词的句子塞进答案。
        for (int need = 2; need >= 1 && picked.size() < 3; need--) {
            if (need == 1 && !picked.isEmpty()) {
                break;
            }
            for (Candidate candidate : sorted) {
                if (picked.size() >= 3) {
                    break;
                }
                if (candidate.distinct() >= need && seen.add(candidate.text())) {
                    picked.add(candidate.order() + "\u0000" + candidate.text());
                }
            }
        }

        // 恢复原文顺序，读起来才连贯
        List<String> ordered = new ArrayList<>();
        picked.stream().sorted().forEach(item -> ordered.add(item.substring(item.indexOf('\u0000') + 1)));

        if (ordered.isEmpty()) {
            for (String raw : splitSentences(hits.get(0).content())) {
                String rawTrimmed = raw.strip();
                if (rawTrimmed.startsWith("#")) {
                    continue;
                }
                String sentence = cleanSentence(raw);
                if (sentence.length() >= 10 && !looksLikeHeading(sentence)) {
                    ordered.add(sentence);
                }
                if (ordered.size() >= 2) {
                    break;
                }
            }
        }

        StringBuilder answer = new StringBuilder("根据知识库资料：");
        for (String sentence : ordered) {
            answer.append(sentence);
            if (!sentence.endsWith("。") && !sentence.endsWith("；") && !sentence.endsWith("：")) {
                answer.append("。");
            }
        }
        return answer.toString();
    }

    private static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /** 清洗 markdown 标记与多余空白，只保留正文。 */
    private static String cleanSentence(String raw) {
        return raw.replaceAll("^#+\\s*", "")
                .replace("**", "")
                .replace("`", "")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /** 标题行（如「一、岗位职责」「二、硬性技能要求」）不含答案，必须排除。 */
    private static boolean looksLikeHeading(String sentence) {
        return sentence.matches("^[一二三四五六七八九十]+、.*")
                || sentence.matches("^第[一二三四五六七八九十]+.*[部分节].*")
                || sentence.endsWith("：")
                || sentence.startsWith("（一）")
                || sentence.startsWith("（二）");
    }

    private static List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char c : text.toCharArray()) {
            current.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '\n') {
                sentences.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            sentences.add(current.toString());
        }
        return sentences;
    }

    private static String excerpt(String content, int max) {
        String cleaned = content.replace("\n", " ").replaceAll("\\s+", " ").strip();
        return cleaned.length() <= max ? cleaned : cleaned.substring(0, max) + "…";
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", db.count("SELECT COUNT(*) FROM kb_doc_ref"));
        result.put("chunks", vectorStore.count());
        result.put("vectorStore", vectorStore.describe());
        result.put("embedding", embeddingService.describe());
        result.put("topK", topK);
        result.put("minScore", minScore);
        result.put("retrievals", db.count("SELECT COUNT(*) FROM kb_retrieval_log"));
        result.put("refusals", db.count("SELECT COUNT(*) FROM kb_retrieval_log WHERE refused=1"));
        result.put("avgTopScore", db.one("SELECT ROUND(AVG(top_score),4) AS v FROM kb_retrieval_log"));
        result.put("docs", db.camelList("SELECT code, title, source, category, chunk_count FROM kb_doc_ref ORDER BY id"));
        return result;
    }
}
