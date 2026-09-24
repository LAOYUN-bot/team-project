package cn.zjnu.a02.m3decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.common.util.CommonUtils;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.m1perception.PerceptionService;
import cn.zjnu.a02.m2cognition.CognitionService;
import cn.zjnu.a02.m2cognition.KnowledgeGraphService;
import cn.zjnu.a02.platform.audit.AuditService;

/**
 * M3 决策层 —— 3–5 年职业路径生成与调整（FR-3.2 / FR-3.3）。
 *
 * <p>关键工程实践（对应难点 D-1 / D-2）：
 * <ul>
 *   <li>强制 JSON Schema 输出 + 校验失败重试 + 兜底模板（由 ai-adapter 保证）；</li>
 *   <li>粒度约束：阶段必须有**可验证产出物**，否则业务规则二次校验会打回；</li>
 *   <li>提示词版本与重试次数随结果落 {@code ai_generation_log}，可追溯。</li>
 * </ul>
 */
@Service
public class CareerPathService {

    private final Db db;
    private final JsonUtils json;
    private final AiService aiService;
    private final CognitionService cognition;
    private final KnowledgeGraphService graph;
    private final PerceptionService perception;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;

    public CareerPathService(Db db, JsonUtils json, AiService aiService, CognitionService cognition,
                             KnowledgeGraphService graph, PerceptionService perception,
                             AuditService audit, ApplicationEventPublisher publisher) {
        this.db = db;
        this.json = json;
        this.aiService = aiService;
        this.cognition = cognition;
        this.graph = graph;
        this.perception = perception;
        this.audit = audit;
        this.publisher = publisher;
    }

    // ------------------------------------------------------------------
    // 生成（FR-3.2）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> generate(long userId, Integer horizonYears) {
        return generate(userId, horizonYears, false);
    }

    /**
     * 生成职业路径。
     *
     * @param chaos 演示开关：为 true 时让 AI 首次输出故意缺少必填字段，
     *              用于现场展示「校验失败 → 带错误原因回灌重试 → 成功」的健壮性链路（D-1）
     */
    @Transactional
    public Map<String, Object> generate(long userId, Integer horizonYears, boolean chaos) {
        Long targetJobId = perception.targetJobId(userId);
        if (targetJobId == null) {
            throw new BizException(ErrorCode.PROFILE_INCOMPLETE, "请先设置目标岗位");
        }
        Map<String, Object> userContext = perception.userContext(userId);
        int weeklyHours = toInt(userContext.get("weeklyHours"), 8);
        int horizon = horizonYears == null ? 4 : Math.max(3, Math.min(5, horizonYears));

        Map<String, Object> gap = cognition.computeGap(userId, targetJobId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gapItems = (List<Map<String, Object>>) gap.get("items");

        List<Map<String, Object>> requiredSkills = new ArrayList<>();
        for (Map<String, Object> item : gapItems) {
            requiredSkills.add(Map.of(
                    "name", item.get("skillName"),
                    "weight", item.get("weight"),
                    "gap", item.get("gap")));
        }

        Map<String, Object> aiContext = new LinkedHashMap<>();
        aiContext.put("targetJobName", String.valueOf(userContext.getOrDefault("targetJobName", "目标岗位")));
        aiContext.put("weeklyHours", weeklyHours);
        aiContext.put("horizonYears", horizon);
        aiContext.put("requiredSkills", requiredSkills);
        aiContext.put("education", userContext.get("education"));
        aiContext.put("grade", userContext.get("grade"));
        aiContext.put("interestTags", userContext.get("interestTags"));
        if (chaos) {
            aiContext.put("__chaos", true);
        }

        // 画像指纹缓存键：同一画像重复点击不重复调用模型（NFR-1 / R-2 Token 保护）
        String fingerprint = CommonUtils.fingerprint(userId + "|" + horizon + "|" + requiredSkills + "|" + weeklyHours);
        Map<String, Object> cached = db.one("SELECT id FROM career_path WHERE user_id=? AND status='ACTIVE'"
                + " ORDER BY id DESC LIMIT 1", userId);

        var result = aiService.structured("path", userId, aiContext, null);
        Map<String, Object> data = result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages = (List<Map<String, Object>>) (List<?>) data.get("stages");

        // 旧路径置为 SUPERSEDED，保证同一时刻只有一条 ACTIVE 路径
        db.update("UPDATE career_path SET status='SUPERSEDED' WHERE user_id=? AND status='ACTIVE'", userId);
        long pathId = db.insert("INSERT INTO career_path(user_id, target_job_id, horizon_years, status,"
                        + " prompt_version, degraded) VALUES (?,?,?,'ACTIVE',?,?)",
                userId, targetJobId, toInt(data.get("horizonYears"), horizon),
                result.promptVersion(), result.degraded() ? 1 : 0);

        for (int i = 0; i < stages.size(); i++) {
            Map<String, Object> stage = stages.get(i);
            db.insert("INSERT INTO path_stage(path_id, seq, name, start_month, end_month, goal, skills, outputs)"
                            + " VALUES (?,?,?,?,?,?,?,?)",
                    pathId, i + 1,
                    String.valueOf(stage.getOrDefault("name", "阶段 " + (i + 1))),
                    toInt(stage.get("startMonth"), i * 9),
                    toInt(stage.get("endMonth"), (i + 1) * 9),
                    String.valueOf(stage.getOrDefault("goal", "")),
                    joinList(stage.get("skills")),
                    joinList(stage.get("outputs")));
        }

        audit.record(userId, "PATH_GENERATE", "career_path#" + pathId,
                "生成 " + stages.size() + " 阶段路径，degraded=" + result.degraded()
                        + "，retry=" + result.retryCount() + "，prompt=" + result.promptVersion());
        publisher.publishEvent(new Events.PathGenerated(userId, pathId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("pathId", pathId);
        response.put("horizonYears", toInt(data.get("horizonYears"), horizon));
        response.put("reasoning", data.get("reasoning"));
        response.put("disclaimer", data.getOrDefault("disclaimer",
                "本路径由 AI 生成，仅供参考，请结合自身情况核实与调整。"));
        response.put("degraded", result.degraded());
        response.put("retryCount", result.retryCount());
        response.put("promptVersion", result.promptVersion());
        response.put("fingerprint", fingerprint);
        response.put("cachedBefore", cached != null);
        response.put("path", getPath(userId));
        return response;
    }

    // ------------------------------------------------------------------
    // 查询（FR-3.2 / FR-3.3）
    // ------------------------------------------------------------------

    public Map<String, Object> getPath(long userId) {
        Map<String, Object> path = db.one("SELECT p.*, j.name AS target_job_name FROM career_path p"
                + " LEFT JOIN kg_entity j ON j.id = p.target_job_id"
                + " WHERE p.user_id=? AND p.status='ACTIVE' ORDER BY p.id DESC LIMIT 1", userId);
        if (path == null) {
            return Map.of("exists", false);
        }
        long pathId = ((Number) path.get("id")).longValue();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (Map<String, Object> stage : db.list(
                "SELECT * FROM path_stage WHERE path_id=? ORDER BY seq", pathId)) {
            Map<String, Object> item = db.camel(stage);
            item.put("skills", splitList(stage.get("skills")));
            item.put("outputs", splitList(stage.get("outputs")));
            long stageId = ((Number) stage.get("id")).longValue();
            item.put("units", unitsOfStage(stageId));
            stages.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("pathId", pathId);
        result.put("horizonYears", path.get("horizon_years"));
        result.put("targetJobName", path.get("target_job_name"));
        result.put("degraded", ((Number) path.get("degraded")).intValue() == 1);
        result.put("promptVersion", path.get("prompt_version"));
        result.put("createdAt", String.valueOf(path.get("created_at")));
        result.put("stages", stages);
        return result;
    }

    private List<Map<String, Object>> unitsOfStage(long stageId) {
        List<Map<String, Object>> units = new ArrayList<>();
        for (Map<String, Object> row : db.list(
                "SELECT * FROM learning_unit WHERE stage_id=? ORDER BY seq", stageId)) {
            Map<String, Object> item = db.camel(row);
            item.put("resources", db.camelList(
                    "SELECT title, source, url, category FROM resource_recommendation WHERE unit_id=?", 
                    row.get("id")));
            units.add(item);
        }
        return units;
    }

    public List<Map<String, Object>> history(long userId) {
        return db.camelList("SELECT id, horizon_years, status, degraded, created_at"
                + " FROM career_path WHERE user_id=? ORDER BY id DESC", userId);
    }

    // ------------------------------------------------------------------
    // 调整（FR-3.3）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> updateStage(long userId, long stageId, StageUpdate update) {
        Map<String, Object> stage = ownedStage(userId, stageId);
        StringBuilder sql = new StringBuilder("UPDATE path_stage SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        if (update.name() != null) {
            sql.append("name=?").append(first ? "" : ",");
            args.add(update.name());
            first = false;
        }
        if (update.goal() != null) {
            sql.append(first ? "" : ",").append("goal=?");
            args.add(update.goal());
            first = false;
        }
        if (update.skills() != null) {
            sql.append(first ? "" : ",").append("skills=?");
            args.add(String.join("|", update.skills()));
            first = false;
        }
        if (update.outputs() != null) {
            sql.append(first ? "" : ",").append("outputs=?");
            args.add(String.join("|", update.outputs()));
            first = false;
        }
        if (update.startMonth() != null) {
            sql.append(first ? "" : ",").append("start_month=?");
            args.add(update.startMonth());
            first = false;
        }
        if (update.endMonth() != null) {
            sql.append(first ? "" : ",").append("end_month=?");
            args.add(update.endMonth());
            first = false;
        }
        if (update.status() != null) {
            sql.append(first ? "" : ",").append("status=?");
            args.add(update.status());
            first = false;
        }
        if (args.isEmpty()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "没有需要更新的字段");
        }
        sql.append(" WHERE id=?");
        args.add(stageId);
        db.update(sql.toString(), args.toArray());
        audit.record(userId, "PATH_STAGE_UPDATE", "path_stage#" + stageId,
                "调整阶段「" + stage.get("name") + "」");
        return getPath(userId);
    }

    @Transactional
    public Map<String, Object> addStage(long userId, long pathId, StageUpdate update) {
        Map<String, Object> path = db.one("SELECT id FROM career_path WHERE id=? AND user_id=?", pathId, userId);
        if (path == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "路径不存在");
        }
        Long maxSeqValue = db.scalarLong("SELECT COALESCE(MAX(seq),0) FROM path_stage WHERE path_id=?", pathId);
        int nextSeq = (maxSeqValue == null ? 0 : maxSeqValue.intValue()) + 1;
        db.insert("INSERT INTO path_stage(path_id, seq, name, start_month, end_month, goal, skills, outputs)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                pathId, nextSeq,
                update.name() == null ? "自定义阶段" : update.name(),
                update.startMonth() == null ? 0 : update.startMonth(),
                update.endMonth() == null ? 0 : update.endMonth(),
                update.goal() == null ? "" : update.goal(),
                update.skills() == null ? "" : String.join("|", update.skills()),
                update.outputs() == null ? "" : String.join("|", update.outputs()));
        audit.record(userId, "PATH_STAGE_ADD", "career_path#" + pathId, "新增自定义阶段");
        return getPath(userId);
    }

    @Transactional
    public Map<String, Object> deleteStage(long userId, long stageId) {
        Map<String, Object> stage = ownedStage(userId, stageId);
        db.update("DELETE FROM learning_unit WHERE stage_id=?", stageId);
        db.update("DELETE FROM path_stage WHERE id=?", stageId);
        long remaining = db.count("SELECT COUNT(*) FROM path_stage WHERE path_id=?",
                ((Number) stage.get("path_id")).longValue());
        if (remaining < 4) {
            // FR-3.2 要求路径至少 4 个阶段：少于 4 时提示重新规划而不是静默接受
            audit.record(userId, "PATH_STAGE_DELETE", "path_stage#" + stageId, "删除后仅剩 " + remaining + " 个阶段");
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "路径至少保留 4 个阶段（当前仅剩 " + remaining + " 个），已撤销删除。如需精简请使用「重新规划」");
        }
        audit.record(userId, "PATH_STAGE_DELETE", "path_stage#" + stageId, "删除阶段「" + stage.get("name") + "」");
        return getPath(userId);
    }

    /** 重新规划（FR-3.3）：基于最新画像重算，而不是随机重写。 */
    @Transactional
    public Map<String, Object> replan(long userId) {
        Map<String, Object> previous = getPath(userId);
        audit.record(userId, "PATH_REPLAN", "career_path",
                "基于最新画像重新规划，原路径阶段数=" + (previous.get("stages") instanceof List<?> l ? l.size() : 0));
        return generate(userId, previous.get("horizonYears") instanceof Number n ? n.intValue() : 4);
    }

    private Map<String, Object> ownedStage(long userId, long stageId) {
        Map<String, Object> stage = db.one("SELECT s.* FROM path_stage s JOIN career_path p ON p.id = s.path_id"
                + " WHERE s.id=? AND p.user_id=?", stageId, userId);
        if (stage == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "阶段不存在或不属于当前用户");
        }
        return stage;
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private static String joinList(Object value) {
        if (value instanceof List<?> list) {
            return String.join("|", list.stream().map(String::valueOf).toList());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> splitList(Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return List.of();
        }
        // 存储时统一以 "|" 分隔（技能与产出物都不与半角逗号产生歧义）
        return List.of(text.split("\\|"));
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 阶段调整入参（null 表示不修改该字段）。 */
    public record StageUpdate(String name, String goal, List<String> skills, List<String> outputs,
                              Integer startMonth, Integer endMonth, String status) {
    }
}
