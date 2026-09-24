package cn.zjnu.a02.m3decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.common.port.RadarQueryPort;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * M3 决策层 —— 能力雷达版本化服务（FR-3.1，AD-4）。
 *
 * <p>不可变性约束：**版本一旦生成即只读**，任何重算都会产生新版本。
 * 这样「两时点对比」（FR-6.2）退化为两个 radar_id 做差，无需历史回溯逻辑；
 * 「画像动态迭代」（难题 P1）表现为版本号递增 + {@code triggered_by} 可追溯。
 */
@Service
public class RadarService implements RadarQueryPort {

    private final Db db;
    private final JsonUtils json;
    private final ApplicationEventPublisher publisher;

    public RadarService(Db db, JsonUtils json, ApplicationEventPublisher publisher) {
        this.db = db;
        this.json = json;
        this.publisher = publisher;
    }

    // ==================================================================
    // RadarQueryPort 实现
    // ==================================================================

    @Override
    public Map<String, Double> latestScores(long userId) {
        Map<String, Object> row = db.one(
                "SELECT id FROM competency_radar WHERE user_id=? ORDER BY version DESC LIMIT 1", userId);
        return row == null ? Map.of() : scoresOfRadar(((Number) row.get("id")).longValue());
    }

    @Override
    public Map<String, Double> scoresOfVersion(long userId, int version) {
        Map<String, Object> row = db.one(
                "SELECT id FROM competency_radar WHERE user_id=? AND version=?", userId, version);
        return row == null ? Map.of() : scoresOfRadar(((Number) row.get("id")).longValue());
    }

    @Override
    public int latestVersion(long userId) {
        Long max = db.scalarLong("SELECT MAX(version) FROM competency_radar WHERE user_id=?", userId);
        return max == null ? 0 : max.intValue();
    }

    private Map<String, Double> scoresOfRadar(long radarId) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map<String, Object> row : db.list(
                "SELECT dimension_key, weighted_score FROM radar_dimension_score WHERE radar_id=?", radarId)) {
            scores.put(String.valueOf(row.get("dimension_key")),
                    ((Number) row.get("weighted_score")).doubleValue());
        }
        return scores;
    }

    // ==================================================================
    // 版本创建
    // ==================================================================

    /** 首次建档：若尚无画像则生成 v1。 */
    @Transactional
    public Map<String, Object> ensureInitialRadar(long userId, Long targetJobId) {
        if (latestVersion(userId) > 0) {
            return latest(userId);
        }
        return createVersion(userId, "ASSESSMENT", targetJobId);
    }

    /**
     * 生成新的雷达版本。
     *
     * @param triggeredBy ASSESSMENT / SIMULATION / TASK / MANUAL / REPLAN（D-3 可追溯）
     */
    @Transactional
    public Map<String, Object> createVersion(long userId, String triggeredBy, Long targetJobId) {
        Map<String, Double> assessment = latestAssessmentScores(userId);
        Map<String, Double> simulation = simulationDimensionScores(userId);
        Double completion = taskCompletionRate(userId);
        Double streak = streakScore(userId);
        Double timeliness = timelinessScore(userId);
        Double project = projectTagScore(userId);
        Map<String, Double> previous = latestScores(userId);
        double alpha = configDouble("radar.decay.alpha", 0.35);

        RadarCalculator.RadarOutcome outcome = RadarCalculator.compute(
                new RadarCalculator.RadarInput(assessment, simulation, completion, streak,
                        timeliness, project, previous, alpha),
                triggeredBy);

        int nextVersion = latestVersion(userId) + 1;
        long radarId = db.insert("INSERT INTO competency_radar"
                        + "(user_id, version, target_job_id, triggered_by, formula_version, calc_input_json)"
                        + " VALUES (?,?,?,?,?,?)",
                userId, nextVersion, targetJobId, triggeredBy,
                outcome.formulaVersion(), json.stringify(outcome.calcInput()));

        for (RadarCalculator.DimensionOutcome dim : outcome.dimensions()) {
            db.insert("INSERT INTO radar_dimension_score"
                            + "(radar_id, dimension_key, raw_score, weighted_score, source_detail_json)"
                            + " VALUES (?,?,?,?,?)",
                    radarId, dim.key(), dim.rawScore(), dim.finalScore(),
                    json.stringify(Map.of(
                            "contributions", dim.contributions(),
                            "previousScore", dim.previousScore(),
                            "decayApplied", dim.decayApplied(),
                            "alpha", alpha,
                            "label", dim.label())));
        }

        publisher.publishEvent(new Events.RadarVersionCreated(userId, radarId, nextVersion, triggeredBy));
        return describe(radarId);
    }

    /** 完整描述一个雷达版本（含来源明细，供界面展示「分值从哪来」）。 */
    public Map<String, Object> describe(long radarId) {
        Map<String, Object> radar = db.one("SELECT * FROM competency_radar WHERE id=?", radarId);
        if (radar == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "画像版本不存在");
        }
        List<Map<String, Object>> dims = new ArrayList<>();
        for (Map<String, Object> row : db.list(
                "SELECT dimension_key, raw_score, weighted_score, source_detail_json"
                        + " FROM radar_dimension_score WHERE radar_id=? ORDER BY id", radarId)) {
            Map<String, Object> detail = json.parseMap(String.valueOf(row.get("source_detail_json")));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", row.get("dimension_key"));
            item.put("label", detail.getOrDefault("label",
                    RadarCalculator.DIMENSION_LABELS.get(String.valueOf(row.get("dimension_key")))));
            item.put("score", ((Number) row.get("weighted_score")).doubleValue());
            item.put("rawScore", ((Number) row.get("raw_score")).doubleValue());
            item.put("previousScore", detail.get("previousScore"));
            item.put("decayApplied", detail.get("decayApplied"));
            item.put("contributions", detail.get("contributions"));
            dims.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("radarId", radarId);
        result.put("version", ((Number) radar.get("version")).intValue());
        result.put("triggeredBy", radar.get("triggered_by"));
        result.put("formulaVersion", radar.get("formula_version"));
        result.put("targetJobId", radar.get("target_job_id"));
        result.put("createdAt", String.valueOf(radar.get("created_at")));
        result.put("dimensions", dims);
        result.put("calcInput", json.parseMap(String.valueOf(radar.get("calc_input_json"))));
        result.put("weightTable", RadarCalculator.weightTable());
        result.put("disclaimer", "能力分值由可审计公式计算得出（非模型编造），AI 生成内容仅供参考。");
        return result;
    }

    /** 用户最新画像。 */
    public Map<String, Object> latest(long userId) {
        Map<String, Object> row = db.one(
                "SELECT id FROM competency_radar WHERE user_id=? ORDER BY version DESC LIMIT 1", userId);
        if (row == null) {
            throw new BizException(ErrorCode.NO_RADAR);
        }
        return describe(((Number) row.get("id")).longValue());
    }

    /** 版本历史列表（FR-3.1「历史版本可对比」）。 */
    public List<Map<String, Object>> versions(long userId) {
        return db.camelList("SELECT r.id AS radar_id, r.version, r.triggered_by, r.formula_version,"
                + " r.created_at, (SELECT ROUND(AVG(weighted_score),1) FROM radar_dimension_score d"
                + "  WHERE d.radar_id = r.id) AS avg_score"
                + " FROM competency_radar r WHERE r.user_id=? ORDER BY r.version", userId);
    }

    /** 两时点对比（FR-6.2）：取两个版本做差，无需历史回溯。 */
    public Map<String, Object> compare(long userId, int versionA, int versionB) {
        Map<String, Object> a = describe(radarIdOf(userId, versionA));
        Map<String, Object> b = describe(radarIdOf(userId, versionB));
        Map<String, Double> scoresA = new LinkedHashMap<>();
        for (Object item : (List<?>) a.get("dimensions")) {
            if (item instanceof Map<?, ?> m) {
                scoresA.put(String.valueOf(m.get("key")), ((Number) m.get("score")).doubleValue());
            }
        }
        List<Map<String, Object>> diff = new ArrayList<>();
        double sumA = 0;
        double sumB = 0;
        List<String> improved = new ArrayList<>();
        List<String> declined = new ArrayList<>();
        for (Object item : (List<?>) b.get("dimensions")) {
            if (item instanceof Map<?, ?> m) {
                String key = String.valueOf(m.get("key"));
                double scoreB = ((Number) m.get("score")).doubleValue();
                double scoreA = scoresA.getOrDefault(key, scoreB);
                double delta = Math.round((scoreB - scoreA) * 10) / 10.0;
                sumA += scoreA;
                sumB += scoreB;
                if (delta > 0.05) {
                    improved.add(String.valueOf(m.get("label")));
                } else if (delta < -0.05) {
                    declined.add(String.valueOf(m.get("label")));
                }
                diff.add(Map.of("key", key, "label", m.get("label"),
                        "scoreA", scoreA, "scoreB", scoreB, "delta", delta));
            }
        }
        double avgA = Math.round(sumA / Math.max(1, diff.size()) * 10) / 10.0;
        double avgB = Math.round(sumB / Math.max(1, diff.size()) * 10) / 10.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionA", versionA);
        result.put("versionB", versionB);
        result.put("avgA", avgA);
        result.put("avgB", avgB);
        result.put("avgDelta", Math.round((avgB - avgA) * 10) / 10.0);
        result.put("diff", diff);
        result.put("improved", improved);
        result.put("declined", declined);
        result.put("summary", improved.isEmpty()
                ? "两个版本之间未出现明显提升维度，建议复盘学习投入是否落在目标技能上。"
                : "对比显示「" + String.join("、", improved) + "」等 " + improved.size() + " 个维度有所提升，"
                  + "平均分从 " + avgA + " 变为 " + avgB + "。");
        return result;
    }

    private long radarIdOf(long userId, int version) {
        Map<String, Object> row = db.one(
                "SELECT id FROM competency_radar WHERE user_id=? AND version=?", userId, version);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "画像版本 v" + version + " 不存在");
        }
        return ((Number) row.get("id")).longValue();
    }

    // ==================================================================
    // 各来源分值采集（全部来自数据库，可审计）
    // ==================================================================

    private Map<String, Double> latestAssessmentScores(long userId) {
        Map<String, Object> row = db.one(
                "SELECT score_json FROM assessment_record WHERE user_id=? ORDER BY id DESC LIMIT 1", userId);
        if (row == null) {
            return null;
        }
        Map<String, Object> raw = json.parseMap(String.valueOf(row.get("score_json")));
        Map<String, Double> scores = new LinkedHashMap<>();
        raw.forEach((k, v) -> scores.put(k, v instanceof Number n ? n.doubleValue() : null));
        return scores;
    }

    /** 训练评分 → 雷达维度：按维度键聚合用户历史评分的平均值。 */
    private Map<String, Double> simulationDimensionScores(long userId) {
        List<Map<String, Object>> rows = db.list(
                "SELECT s.dim_key, AVG(s.score) AS avg_score FROM evaluation_score s"
                        + " JOIN evaluation e ON e.id = s.evaluation_id"
                        + " WHERE e.user_id=? GROUP BY s.dim_key", userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            scores.put(String.valueOf(row.get("dim_key")), ((Number) row.get("avg_score")).doubleValue());
        }
        return scores;
    }

    private Double taskCompletionRate(long userId) {
        long total = db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=?", userId);
        if (total == 0) {
            return null;
        }
        long done = db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND status='DONE'", userId);
        return done * 100.0 / total;
    }

    private Double streakScore(long userId) {
        long streak = currentStreak(userId);
        if (streak == 0 && db.count("SELECT COUNT(*) FROM task_checkin WHERE user_id=?", userId) == 0) {
            return null;
        }
        return Math.min(100.0, streak * 12.0);
    }

    private Double timelinessScore(long userId) {
        long total = db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND due_date IS NOT NULL", userId);
        if (total == 0) {
            return null;
        }
        long onTime = db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND due_date IS NOT NULL"
                + " AND done_at IS NOT NULL AND CAST(done_at AS DATE) <= due_date", userId);
        return onTime * 100.0 / total;
    }

    private Double projectTagScore(long userId) {
        long tags = db.count("SELECT COUNT(*) FROM resume_extract_tag WHERE user_id=? AND confirmed=1", userId);
        if (tags == 0) {
            return null;
        }
        return Math.min(100.0, 55 + tags * 6.0);
    }

    /** 连续打卡天数（截至今天或昨天）。 */
    public long currentStreak(long userId) {
        List<Map<String, Object>> rows = db.list(
                "SELECT checkin_date FROM task_checkin WHERE user_id=? ORDER BY checkin_date DESC LIMIT 60",
                userId);
        if (rows.isEmpty()) {
            return 0;
        }
        long streak = 0;
        java.time.LocalDate expected = java.time.LocalDate.now();
        for (Map<String, Object> row : rows) {
            java.time.LocalDate date = toLocalDate(row.get("checkin_date"));
            if (date == null) {
                continue;
            }
            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (streak == 0 && date.equals(expected.minusDays(1))) {
                // 今天还没打卡，从昨天开始算
                streak++;
                expected = date.minusDays(1);
            } else {
                break;
            }
        }
        return streak;
    }

    private static java.time.LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.time.LocalDate d) {
            return d;
        }
        if (value == null) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(String.valueOf(value).substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private double configDouble(String key, double defaultValue) {
        Map<String, Object> row = db.one("SELECT cfg_value FROM sys_config WHERE cfg_key=?", key);
        if (row == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(row.get("cfg_value")));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
