package cn.zjnu.a02.m4generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.m1perception.PerceptionService;
import cn.zjnu.a02.m2cognition.KnowledgeGraphService;

/**
 * M4 生成层 —— 学习单元拆解与资源推荐（FR-4.1 / FR-4.2）。
 *
 * <p>关键约束（难点 D-2「路径空泛化」）：每个学习单元必须带**可验证产出物**与预计工时，
 * 且工时不得超过用户每周可用学时。单元拆解由 ai-adapter 的 unit 场景完成，
 * 校验失败会走「重试 → 兜底模板」链路。
 */
@Service
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

    private final Db db;
    private final AiService aiService;
    private final KnowledgeGraphService graph;
    private final PerceptionService perception;

    public GenerationService(Db db, AiService aiService, KnowledgeGraphService graph,
                             PerceptionService perception) {
        this.db = db;
        this.aiService = aiService;
        this.graph = graph;
        this.perception = perception;
    }

    /** 路径生成后自动拆解学习单元（闭环：④ 的输入来自 ③ 的持久化结果）。 */
    @EventListener
    @Transactional
    public void onPathGenerated(Events.PathGenerated event) {
        try {
            for (Map<String, Object> stage : db.list(
                    "SELECT id FROM path_stage WHERE path_id=? ORDER BY seq", event.pathId())) {
                generateUnitsForStage(event.userId(), ((Number) stage.get("id")).longValue());
            }
            log.info("路径生成 → 已拆解学习单元 userId={} pathId={}", event.userId(), event.pathId());
        } catch (Exception e) {
            log.warn("学习单元拆解失败 userId={} err={}", event.userId(), e.getMessage());
        }
    }

    /** 为某个阶段生成周学习单元（幂等：重复调用会先清理旧单元）。 */
    @Transactional
    public Map<String, Object> generateUnitsForStage(long userId, long stageId) {
        Map<String, Object> stage = db.one("SELECT s.*, p.user_id FROM path_stage s"
                + " JOIN career_path p ON p.id = s.path_id WHERE s.id=? AND p.user_id=?", stageId, userId);
        if (stage == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "阶段不存在或不属于当前用户");
        }
        Map<String, Object> userContext = perception.userContext(userId);
        int weeklyHours = toInt(userContext.get("weeklyHours"), 8);

        List<Map<String, Object>> stageSkills = new ArrayList<>();
        for (String name : splitList(stage.get("skills"))) {
            stageSkills.add(Map.of("name", name));
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("stageName", String.valueOf(stage.get("name")));
        context.put("stageGoal", String.valueOf(stage.get("goal")));
        context.put("stageSkills", stageSkills);
        context.put("weeklyHours", weeklyHours);
        context.put("unitCount", 6);

        var result = aiService.structured("unit", userId, context, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> units = (List<Map<String, Object>>) (List<?>) result.data().get("units");

        db.update("DELETE FROM resource_recommendation WHERE unit_id IN"
                + " (SELECT id FROM learning_unit WHERE stage_id=?)", stageId);
        db.update("DELETE FROM learning_unit WHERE stage_id=?", stageId);

        int seq = 1;
        for (Map<String, Object> unit : units) {
            long unitId = db.insert("INSERT INTO learning_unit(stage_id, user_id, seq, period, title,"
                            + " deliverable, est_hours, status) VALUES (?,?,?,?,?,?,?,'TODO')",
                    stageId, userId, seq++,
                    String.valueOf(unit.getOrDefault("period", "W" + seq)),
                    String.valueOf(unit.getOrDefault("title", "学习单元")),
                    String.valueOf(unit.getOrDefault("deliverable", "")),
                    Math.min(weeklyHours, toInt(unit.get("estHours"), 6)));
            recommendForUnit(userId, unitId, String.valueOf(unit.getOrDefault("skill", "")));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("stageId", stageId);
        response.put("unitCount", units.size());
        response.put("degraded", result.degraded());
        response.put("retryCount", result.retryCount());
        response.put("promptVersion", result.promptVersion());
        response.put("units", unitsOfStage(stageId));
        return response;
    }

    /** 依据技能从知识图谱取课程资源（每条必须带来源，FR-4.2）。 */
    private void recommendForUnit(long userId, long unitId, String skillName) {
        List<Map<String, Object>> courses = new ArrayList<>();
        if (!skillName.isBlank()) {
            courses = db.camelList("SELECT c.id AS course_id, c.name AS course_name, c.attrs_json"
                    + " FROM kg_entity c JOIN kg_relation r ON r.subject_id = c.id"
                    + " JOIN kg_entity s ON s.id = r.object_id"
                    + " WHERE r.predicate='TEACHES' AND s.name=? LIMIT 2", skillName);
        }
        if (courses.isEmpty()) {
            courses = db.camelList("SELECT id AS course_id, name AS course_name, attrs_json"
                    + " FROM kg_entity WHERE type='COURSE' ORDER BY id LIMIT 1");
        }
        for (Map<String, Object> course : courses) {
            db.insert("INSERT INTO resource_recommendation(user_id, unit_id, title, source, url, category)"
                            + " VALUES (?,?,?,?,?,?)",
                    userId, unitId, String.valueOf(course.get("courseName")),
                    "知识图谱课程库（" + String.valueOf(course.getOrDefault("attrsJson", "公开课程")) + "）",
                    null, "COURSE");
        }
    }

    public List<Map<String, Object>> units(long userId) {
        List<Map<String, Object>> units = new ArrayList<>();
        for (Map<String, Object> row : db.list("SELECT u.*, s.name AS stage_name FROM learning_unit u"
                + " JOIN path_stage s ON s.id = u.stage_id WHERE u.user_id=? ORDER BY s.seq, u.seq", userId)) {
            Map<String, Object> item = db.camel(row);
            item.put("resources", db.camelList("SELECT title, source, url, category"
                    + " FROM resource_recommendation WHERE unit_id=?", row.get("id")));
            units.add(item);
        }
        return units;
    }

    public List<Map<String, Object>> unitsOfStage(long stageId) {
        return db.camelList("SELECT * FROM learning_unit WHERE stage_id=? ORDER BY seq", stageId);
    }

    /** 资源推荐接口（FR-4.2）：按技能名检索课程，可用真实图谱数据。 */
    public Map<String, Object> recommend(long userId, String skill) {
        if (skill == null || skill.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请指定技能关键词");
        }
        List<Map<String, Object>> rows = db.camelList(
                "SELECT c.name AS title, c.attrs_json, '知识图谱课程库' AS source"
                        + " FROM kg_entity c JOIN kg_relation r ON r.subject_id = c.id"
                        + " JOIN kg_entity s ON s.id = r.object_id"
                        + " WHERE r.predicate='TEACHES' AND (s.name LIKE ? OR s.code LIKE ?)", "%" + skill + "%", "%" + skill + "%");
        if (rows.isEmpty()) {
            rows = db.camelList("SELECT name AS title, attrs_json, '知识图谱课程库' AS source"
                    + " FROM kg_entity WHERE type='COURSE' AND name LIKE ? LIMIT 5", "%" + skill + "%");
        }
        return Map.of("skill", skill, "resources", rows,
                "note", "每条资源均标注来源；无来源的内容不会展示（防幻觉要求）");
    }

    /** 学习资源总览（答辩演示「RAG/知识库」时的数据面）。 */
    public List<Map<String, Object>> kbDocs() {
        return db.camelList("SELECT id, title, source, category, chunk_count, updated_at FROM kb_doc_ref ORDER BY id");
    }

    /** 技能迁移视图（转岗人群）：复用 M2 的闭包表能力。 */
    public Map<String, Object> migration(long fromJobId, long toJobId) {
        return graph.migrationMap(fromJobId, toJobId);
    }

    public KnowledgeGraphService graph() {
        return graph;
    }

    private static List<String> splitList(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        return List.of(String.valueOf(value).split("\\|"));
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
}
