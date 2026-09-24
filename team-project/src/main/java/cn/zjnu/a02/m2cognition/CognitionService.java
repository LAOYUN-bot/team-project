package cn.zjnu.a02.m2cognition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.port.RadarQueryPort;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * M2 认知层 —— 差距分析（FR-2.4）与行业趋势分析（FR-2.2）。
 *
 * <p>差距分析是**算法**（可解释、可复算），趋势分析是**AI 推理**（必须带来源）。
 * 二者分开实现，避免把可计算的结论交给模型「编」。
 */
@Service
public class CognitionService {

    private final Db db;
    private final KnowledgeGraphService graph;
    private final RadarQueryPort radarQuery;
    private final AiService aiService;
    private final JsonUtils json;

    public CognitionService(Db db, KnowledgeGraphService graph, RadarQueryPort radarQuery,
                            AiService aiService, JsonUtils json) {
        this.db = db;
        this.graph = graph;
        this.radarQuery = radarQuery;
        this.aiService = aiService;
        this.json = json;
    }

    // ------------------------------------------------------------------
    // 差距分析（FR-2.4）
    // ------------------------------------------------------------------

    /**
     * 计算「当前能力 vs 岗位要求」的 Top-N 差距项。
     *
     * <p>算法：
     * <pre>
     *   required  = 岗位技能权重(1-5) × 20              → 0-100 的要求分
     *   current   = 该技能所映射能力维度的雷达分值       → 0-100 的现状分
     *   gap       = max(0, required - current)
     *   priority  = weight × gap / 100                  → 排序键（可解释）
     * </pre>
     * 技能 → 维度映射由 {@code SkillDimensionMapper} 依据技能编码关键词确定，
     * 映射规则集中可查，避免"黑盒排序"。
     */
    public Map<String, Object> computeGap(long userId, long jobId) {
        Map<String, Double> radar = radarQuery.latestScores(userId);
        List<Map<String, Object>> model = graph.competencyModel(jobId);
        if (model.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "岗位不存在或尚未配置能力模型");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : model) {
            double weight = toDouble(row.get("weight"), 1);
            double required = weight * 20;
            String dimension = SkillDimensionMapper.dimensionOf(String.valueOf(row.get("skillCode")));
            double current = radar.getOrDefault(dimension, 55.0);
            double gap = Math.max(0, required - current);
            long skillId = ((Number) row.get("skillId")).longValue();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillId", skillId);
            item.put("skillName", row.get("skillName"));
            item.put("skillCode", row.get("skillCode"));
            item.put("weight", weight);
            item.put("requiredScore", round(required));
            item.put("currentScore", round(current));
            item.put("gap", round(gap));
            item.put("priority", round(weight * gap / 100.0));
            item.put("dimension", dimension);
            item.put("dimensionLabel", SkillDimensionMapper.labelOf(dimension));
            item.put("courses", graph.coursesForSkill(skillId).stream().limit(2).toList());
            items.add(item);
        }
        items.sort(Comparator.comparingDouble((Map<String, Object> m) -> toDouble(m.get("priority"), 0)).reversed());

        int topN = Math.min(8, items.size());
        List<Map<String, Object>> top = items.subList(0, topN);
        db.update("DELETE FROM gap_analysis WHERE user_id=? AND job_id=?", userId, jobId);
        db.insert("INSERT INTO gap_analysis(user_id, job_id, items_json) VALUES (?,?,?)",
                userId, jobId, json.stringify(top));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobId", jobId);
        result.put("jobName", graph.entity(jobId).getOrDefault("name", ""));
        result.put("items", top);
        result.put("radarVersion", radarQuery.latestVersion(userId));
        result.put("explain", Map.of(
                "formula", "gap = max(0, 岗位权重×20 − 对应能力维度分值)",
                "sortBy", "priority = 权重 × gap / 100",
                "dimensionMapping", SkillDimensionMapper.describe()));
        return result;
    }

    public Map<String, Object> latestGap(long userId) {
        Map<String, Object> row = db.one("SELECT job_id, items_json, created_at FROM gap_analysis"
                + " WHERE user_id=? ORDER BY id DESC LIMIT 1", userId);
        if (row == null) {
            return Map.of("exists", false);
        }
        return Map.of("exists", true, "jobId", row.get("job_id"),
                "items", json.parseList(String.valueOf(row.get("items_json"))),
                "createdAt", String.valueOf(row.get("created_at")));
    }

    /** 保存差距分析（由 M3 在雷达生成后调用）。 */
    @Transactional
    public void calculateAndSave(long userId, long jobId) {
        computeGap(userId, jobId);
    }

    // ------------------------------------------------------------------
    // 趋势分析（FR-2.2）：无来源不外答
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> analyzeTrend(long userId, String topic) {
        Long jobId = null;
        Map<String, Object> profile = db.one("SELECT target_job_id FROM user_profile WHERE user_id=?", userId);
        if (profile != null && profile.get("target_job_id") != null) {
            jobId = ((Number) profile.get("target_job_id")).longValue();
        }
        if (jobId == null) {
            throw new BizException(ErrorCode.PROFILE_INCOMPLETE, "请先设置目标岗位");
        }

        String jobName = String.valueOf(graph.entity(jobId).getOrDefault("name", "目标岗位"));
        List<Map<String, Object>> kbDocs = db.camelList(
                "SELECT title, source, category FROM kb_doc_ref ORDER BY id");
        List<Map<String, Object>> skills = graph.competencyModel(jobId);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("targetJobName", jobName);
        context.put("topic", topic == null || topic.isBlank() ? jobName + " 技能需求趋势" : topic);
        context.put("kbDocs", kbDocs);
        context.put("requiredSkills", skills);

        var result = aiService.structured("trend", userId, context, null);
        Map<String, Object> data = result.data();

        long reportId = db.insert("INSERT INTO trend_report(user_id, topic, content, sources_json,"
                        + " prompt_version, degraded) VALUES (?,?,?,?,?,?)",
                userId, String.valueOf(data.getOrDefault("topic", jobName + " 趋势")),
                String.valueOf(data.getOrDefault("summary", "")),
                json.stringify(data.getOrDefault("sources", List.of())),
                result.promptVersion(), result.degraded() ? 1 : 0);

        Object sources = data.get("sources");
        if (sources instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    db.insert("INSERT INTO trend_citation(report_id, title, source, url) VALUES (?,?,?,?)",
                            reportId, String.valueOf(m.get("title")), String.valueOf(m.get("source")), null);
                }
            }
        }

        Map<String, Object> response = new LinkedHashMap<>(data);
        response.put("reportId", reportId);
        response.put("degraded", result.degraded());
        response.put("promptVersion", result.promptVersion());
        response.put("retryCount", result.retryCount());
        return response;
    }

    public List<Map<String, Object>> trendHistory(long userId) {
        return db.camelList("SELECT id, topic, content, sources_json, degraded, created_at"
                + " FROM trend_report WHERE user_id=? ORDER BY id DESC LIMIT 10", userId);
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /**
     * 技能 → 能力维度映射（可解释、可调整）。
     *
     * <p>依据技能编码中的关键词判定该技能主要落在哪个能力维度上；
     * 未命中任何规则时归入「专业技能」。M3 计算匹配度时复用同一映射，
     * 保证「差距分析」与「匹配度」口径一致。
     */
    public static final class SkillDimensionMapper {

        private static final Map<String, String> RULES = Map.ofEntries(
                Map.entry("DOCKER", "TOOL_USAGE"), Map.entry("GIT", "TOOL_USAGE"),
                Map.entry("MAVEN", "TOOL_USAGE"), Map.entry("CICD", "TOOL_USAGE"),
                Map.entry("NGINX", "TOOL_USAGE"), Map.entry("LINUX", "TOOL_USAGE"),
                Map.entry("OPENAPI", "TOOL_USAGE"), Map.entry("JUNIT", "TOOL_USAGE"),
                Map.entry("VITEST", "TOOL_USAGE"), Map.entry("ECHARTS", "TOOL_USAGE"),
                Map.entry("EXCEL", "TOOL_USAGE"), Map.entry("TABLEAU", "TOOL_USAGE"),
                Map.entry("BI_REPORT", "TOOL_USAGE"), Map.entry("LANGCHAIN", "TOOL_USAGE"),
                Map.entry("MODEL_SERVING", "TOOL_USAGE"), Map.entry("EMBEDDING", "TOOL_USAGE"),
                Map.entry("VECTOR_DB", "TOOL_USAGE"), Map.entry("MCP_PROTOCOL", "TOOL_USAGE"),
                Map.entry("AI_LITERACY", "TOOL_USAGE"),

                Map.entry("COMMUNICATION", "COMMUNICATION"), Map.entry("PRESENTATION", "COMMUNICATION"),
                Map.entry("TECH_WRITING", "COMMUNICATION"), Map.entry("EMPATHY", "COMMUNICATION"),
                Map.entry("ENGLISH_READING", "COMMUNICATION"), Map.entry("DATA_STORYTELLING", "COMMUNICATION"),

                Map.entry("TEAMWORK", "COLLABORATION"), Map.entry("CROSS_DEPT", "COLLABORATION"),
                Map.entry("CONFLICT_RESOLVE", "COLLABORATION"), Map.entry("SCRUM", "COLLABORATION"),
                Map.entry("LEADERSHIP", "COLLABORATION"),

                Map.entry("AI_ETHICS", "INDUSTRY_KNOW"), Map.entry("DATA_PRIVACY", "INDUSTRY_KNOW"),
                Map.entry("DATA_GOVERNANCE", "INDUSTRY_KNOW"), Map.entry("BIZ_INSIGHT", "INDUSTRY_KNOW"),
                Map.entry("BIZ_UNDERSTANDING", "INDUSTRY_KNOW"), Map.entry("CAREER_PLANNING", "INDUSTRY_KNOW"),
                Map.entry("METRIC_SYSTEM", "INDUSTRY_KNOW"), Map.entry("USER_BEHAVIOR", "INDUSTRY_KNOW"),
                Map.entry("FUNNEL_RETENTION", "INDUSTRY_KNOW"), Map.entry("AB_TEST", "INDUSTRY_KNOW"),

                Map.entry("LEARNING_METHOD", "LEARNING_ABILITY"), Map.entry("TIME_MGMT", "LEARNING_ABILITY"),
                Map.entry("CRITICAL_THINKING", "LEARNING_ABILITY"), Map.entry("PROBLEM_DECOMPOSE", "LEARNING_ABILITY"),
                Map.entry("RESUME_INTERVIEW", "LEARNING_ABILITY"), Map.entry("EMOTION_MGMT", "LEARNING_ABILITY"),

                Map.entry("PROJECT_MGMT", "PROJECT_EXP"), Map.entry("DDD", "PROJECT_EXP"),
                Map.entry("REQUIREMENT_ANALYSIS", "PROJECT_EXP"), Map.entry("HIGH_CONCURRENCY", "PROJECT_EXP"),
                Map.entry("MICROSERVICE", "PROJECT_EXP"), Map.entry("DISTRIBUTED_TX", "PROJECT_EXP"));

        private SkillDimensionMapper() {
        }

        public static String dimensionOf(String skillCode) {
            if (skillCode == null) {
                return "PRO_SKILL";
            }
            for (Map.Entry<String, String> rule : RULES.entrySet()) {
                if (skillCode.contains(rule.getKey())) {
                    return rule.getValue();
                }
            }
            return "PRO_SKILL";
        }

        public static String labelOf(String dimension) {
            return switch (dimension) {
                case "PRO_SKILL" -> "专业技能";
                case "GEN_ABILITY" -> "通用能力";
                case "INDUSTRY_KNOW" -> "行业认知";
                case "PROJECT_EXP" -> "项目经验";
                case "LEARNING_ABILITY" -> "学习力";
                case "COMMUNICATION" -> "沟通表达";
                case "TOOL_USAGE" -> "工具使用";
                case "COLLABORATION" -> "协作意识";
                default -> dimension;
            };
        }

        public static String describe() {
            return "按技能编码关键词映射到 8 个能力维度；未命中规则时归入「专业技能」。"
                    + "规则表见 CognitionService.SkillDimensionMapper，可随岗位模型调整。";
        }
    }
}
