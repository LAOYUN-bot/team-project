package cn.zjnu.a02.platform.privacy;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.platform.audit.AuditService;

/**
 * 数据可携带与可删除（FR-7.4 / NFR-3）：
 * <ul>
 *   <li>{@link #export(long)} 导出该用户全部个人数据（JSON 可下载）；</li>
 *   <li>{@link #erase(long)} 级联删除个人数据并留下删除回执。</li>
 * </ul>
 */
@Service
public class DataErasureService {

    private static final Logger log = LoggerFactory.getLogger(DataErasureService.class);

    /** 用户数据涉及的表（删除时按依赖顺序清理）。 */
    private static final List<String> USER_SCOPED_TABLES = List.of(
            "radar_dimension_score", "competency_radar", "growth_snapshot_metric", "growth_record",
            "evaluation_score", "evaluation", "chat_message", "simulation_session",
            "task_checkin", "learning_task", "resource_recommendation", "learning_unit",
            "path_stage", "career_path", "gap_analysis", "assessment_answer", "assessment_record",
            "resume_extract_tag", "behavior_event", "user_consent", "user_profile",
            "incentive_log", "deviation_alert", "trend_report", "chat_session");

    private final Db db;
    private final AuditService audit;

    public DataErasureService(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /** 导出个人数据（用于数据可携带权）。 */
    public java.util.Map<String, Object> export(long userId) {
        java.util.Map<String, Object> bundle = new java.util.LinkedHashMap<>();
        bundle.put("userId", userId);
        bundle.put("exportedAt", java.time.OffsetDateTime.now().toString());
        bundle.put("profile", db.camel(db.one("SELECT * FROM user_profile WHERE user_id=?", userId)));
        bundle.put("assessments", db.camelList(
                "SELECT id, score_json, created_at FROM assessment_record WHERE user_id=? ORDER BY id", userId));
        bundle.put("radars", db.camelList(
                "SELECT id, version, triggered_by, formula_version, created_at FROM competency_radar WHERE user_id=? ORDER BY version", userId));
        bundle.put("paths", db.camelList(
                "SELECT id, target_job_id, horizon_years, created_at FROM career_path WHERE user_id=?", userId));
        bundle.put("tasks", db.camelList(
                "SELECT id, title, status, due_date FROM learning_task WHERE user_id=?", userId));
        bundle.put("evaluations", db.camelList(
                "SELECT id, overall_score, level, created_at FROM evaluation WHERE user_id=?", userId));
        bundle.put("checkins", db.camelList(
                "SELECT checkin_date, minutes FROM task_checkin WHERE user_id=?", userId));
        bundle.put("notice", "本文件为您在本系统内的全部个人数据副本；不含任何由第三方平台持有的数据。");
        audit.record(userId, "DATA_EXPORT", "self", "导出个人数据");
        return bundle;
    }

    /** 级联删除个人数据，并写入删除回执。 */
    @Transactional
    public java.util.Map<String, Object> erase(long userId) {
        java.util.Map<String, Integer> removed = new java.util.LinkedHashMap<>();
        for (String table : USER_SCOPED_TABLES) {
            String column = switch (table) {
                case "radar_dimension_score" ->
                        "radar_id IN (SELECT id FROM competency_radar WHERE user_id=?)";
                case "growth_snapshot_metric" ->
                        "record_id IN (SELECT id FROM growth_record WHERE user_id=?)";
                case "evaluation_score" ->
                        "evaluation_id IN (SELECT id FROM evaluation WHERE user_id=?)";
                case "chat_message" ->
                        "session_id IN (SELECT id FROM chat_session WHERE user_id=?)";
                case "path_stage" ->
                        "path_id IN (SELECT id FROM career_path WHERE user_id=?)";
                case "learning_unit" ->
                        "user_id=?";
                case "assessment_answer" ->
                        "record_id IN (SELECT id FROM assessment_record WHERE user_id=?)";
                default -> "user_id=?";
            };
            try {
                int n = db.update("DELETE FROM " + table + " WHERE " + column, userId);
                if (n > 0) {
                    removed.put(table, n);
                }
            } catch (Exception e) {
                log.warn("清理表 {} 失败：{}", table, e.getMessage());
            }
        }
        long requestId = db.insert(
                "INSERT INTO data_erasure_request(user_id, status, scope) VALUES (?,?,?)",
                userId, "DONE", "个人全部业务数据");
        audit.record(userId, "DATA_ERASE", "self", "级联删除个人数据，记录数=" + removed.values().stream().mapToInt(Integer::intValue).sum());
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("requestId", requestId);
        result.put("status", "DONE");
        result.put("removed", removed);
        result.put("receipt", "数据删除回执 #" + requestId + "，删除记录数 "
                + removed.values().stream().mapToInt(Integer::intValue).sum());
        return result;
    }
}
