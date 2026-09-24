package cn.zjnu.a02.m2cognition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.db.Db;

/**
 * M2 认知层 —— 职业知识图谱服务（FR-2.1 / FR-2.3，AD-5）。
 *
 * <p>不引入 Neo4j：用「三元组表 {@code kg_relation} + 邻接索引 + 闭包表 {@code kg_path_closure}」
 * 覆盖 ≤3 跳查询。闭包表在应用启动时按需重算（幂等），体现「可重跑的种子数据」要求。
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    /** 参与闭包计算的谓词（技能迁移场景）。 */
    private static final List<String> CLOSURE_PREDICATES = List.of("PREREQUISITE_OF", "RELATED_TO");
    private static final int MAX_DEPTH = 3;

    private final Db db;

    public KnowledgeGraphService(Db db) {
        this.db = db;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<Map<String, Object>> jobs() {
        return db.camelList("SELECT id, code, name, attrs_json FROM kg_entity"
                + " WHERE type='JOB' AND status='ACTIVE' ORDER BY id");
    }

    /** 岗位能力模型：岗位所需技能及权重（FR-2.3）。 */
    public List<Map<String, Object>> competencyModel(long jobId) {
        return db.camelList("SELECT s.id AS skill_id, s.code AS skill_code, s.name AS skill_name,"
                + " r.weight, r.source"
                + " FROM kg_relation r JOIN kg_entity s ON s.id = r.object_id"
                + " WHERE r.subject_id=? AND r.predicate='REQUIRES' AND s.type='SKILL'"
                + " ORDER BY r.weight DESC, s.id", jobId);
    }

    /** 技能 → 对应课程（FR-4.2 资源推荐，反向查 TEACHES）。 */
    public List<Map<String, Object>> coursesForSkill(long skillId) {
        return db.camelList("SELECT c.id AS course_id, c.name AS course_name, c.attrs_json"
                + " FROM kg_relation r JOIN kg_entity c ON c.id = r.subject_id"
                + " WHERE r.object_id=? AND r.predicate='TEACHES' AND c.type='COURSE'", skillId);
    }

    public List<Map<String, Object>> skillsForJob(long jobId) {
        return competencyModel(jobId);
    }

    public List<Map<String, Object>> allSkills() {
        return db.camelList("SELECT id, code, name FROM kg_entity WHERE type='SKILL' ORDER BY id");
    }

    public List<Map<String, Object>> certificates() {
        return db.camelList("SELECT id, code, name, attrs_json FROM kg_entity WHERE type='CERT' ORDER BY id");
    }

    /** 图谱规模统计（用于界面展示「≥3 岗位域、≥100 技能节点」的验收证据）。 */
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entitiesByType", db.camelList(
                "SELECT type, COUNT(*) AS cnt FROM kg_entity GROUP BY type ORDER BY type"));
        result.put("relationsByPredicate", db.camelList(
                "SELECT predicate, COUNT(*) AS cnt FROM kg_relation GROUP BY predicate ORDER BY cnt DESC"));
        result.put("closureRows", db.count("SELECT COUNT(*) FROM kg_path_closure"));
        result.put("jobDomains", db.count("SELECT COUNT(*) FROM kg_entity WHERE type='JOB'"));
        result.put("skillNodes", db.count("SELECT COUNT(*) FROM kg_entity WHERE type='SKILL'"));
        result.put("courseNodes", db.count("SELECT COUNT(*) FROM kg_entity WHERE type='COURSE'"));
        result.put("certNodes", db.count("SELECT COUNT(*) FROM kg_entity WHERE type='CERT'"));
        return result;
    }

    /**
     * 技能迁移映射（服务 U3 转岗人群）：基于闭包表找出 ≤2 跳可迁移技能。
     *
     * @param fromJobId 当前岗位
     * @param toJobId   目标岗位
     */
    public Map<String, Object> migrationMap(long fromJobId, long toJobId) {
        List<Map<String, Object>> fromSkills = competencyModel(fromJobId);
        List<Map<String, Object>> toSkills = competencyModel(toJobId);
        List<Long> toSkillIds = new ArrayList<>();
        for (Map<String, Object> row : toSkills) {
            toSkillIds.add(((Number) row.get("skillId")).longValue());
        }

        List<Map<String, Object>> transferable = new ArrayList<>();
        List<Map<String, Object>> toLearn = new ArrayList<>();
        for (Map<String, Object> row : fromSkills) {
            long skillId = ((Number) row.get("skillId")).longValue();
            if (toSkillIds.contains(skillId)) {
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put("relation", "DIRECT");
                item.put("note", "目标岗位直接要求，可完全复用");
                transferable.add(item);
                continue;
            }
            List<Map<String, Object>> closured = db.camelList(
                    "SELECT d.id AS skill_id, d.name AS skill_name, c.depth, c.path_weight"
                            + " FROM kg_path_closure c JOIN kg_entity d ON d.id = c.descendant_id"
                            + " WHERE c.ancestor_id=? AND c.depth<=2 AND d.id IN ("
                            + "   SELECT object_id FROM kg_relation WHERE subject_id=? AND predicate='REQUIRES')"
                            + " ORDER BY c.depth LIMIT 3", skillId, toJobId);
            if (!closured.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put("relation", "MIGRATABLE");
                item.put("note", "与目标岗位要求的「" + closured.get(0).get("skillName")
                        + "」相关性高（" + closured.get(0).get("depth") + " 跳），只需补齐差异部分");
                item.put("targets", closured);
                transferable.add(item);
            } else {
                toLearn.add(row);
            }
        }
        return Map.of(
                "fromJobId", fromJobId,
                "toJobId", toJobId,
                "transferable", transferable,
                "toLearn", toLearn,
                "savingPercent", fromSkills.isEmpty() ? 0
                        : Math.round(transferable.size() * 1000.0 / fromSkills.size()) / 10.0,
                "note", "技能迁移映射用于生成「压缩式学习路径」，减少转岗人群的重复学习量。");
    }

    public Map<String, Object> entity(long id) {
        Map<String, Object> row = db.one("SELECT id, type, code, name, attrs_json FROM kg_entity WHERE id=?", id);
        return row == null ? Map.of() : db.camel(row);
    }

    // ------------------------------------------------------------------
    // 闭包表维护（启动时重算，幂等）
    // ------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void rebuildClosure() {
        try {
            db.update("DELETE FROM kg_path_closure");
            String inClause = String.join(",", CLOSURE_PREDICATES.stream().map(p -> "'" + p + "'").toList());
            List<Map<String, Object>> edges = db.list(
                    "SELECT subject_id, object_id FROM kg_relation WHERE predicate IN (" + inClause + ")");
            int rows = 0;
            for (Map<String, Object> edge : edges) {
                long from = ((Number) edge.get("subject_id")).longValue();
                long to = ((Number) edge.get("object_id")).longValue();
                rows += insertClosure(from, to, 1, 1.0);
                rows += walk(from, to, 2);
            }
            log.info("知识图谱闭包表重算完成，写入 {} 行，最大跳数 {}", rows, MAX_DEPTH);
        } catch (Exception e) {
            log.warn("闭包表重算失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private int walk(long root, long current, int depth) {
        if (depth > MAX_DEPTH) {
            return 0;
        }
        int rows = 0;
        String inClause = String.join(",", CLOSURE_PREDICATES.stream().map(p -> "'" + p + "'").toList());
        for (Map<String, Object> next : db.list(
                "SELECT object_id FROM kg_relation WHERE subject_id=? AND predicate IN (" + inClause + ")",
                current)) {
            long child = ((Number) next.get("object_id")).longValue();
            if (child == root) {
                continue;
            }
            rows += insertClosure(root, child, depth, 1.0 / depth);
            rows += walk(root, child, depth + 1);
        }
        return rows;
    }

    private int insertClosure(long ancestor, long descendant, int depth, double weight) {
        if (db.count("SELECT COUNT(*) FROM kg_path_closure WHERE ancestor_id=? AND descendant_id=?",
                ancestor, descendant) > 0) {
            return 0;
        }
        db.update("INSERT INTO kg_path_closure(ancestor_id, descendant_id, depth, path_weight)"
                + " VALUES (?,?,?,?)", ancestor, descendant, depth, weight);
        return 1;
    }
}
