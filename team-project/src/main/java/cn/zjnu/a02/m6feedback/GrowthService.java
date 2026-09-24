package cn.zjnu.a02.m6feedback;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.m3decision.RadarCalculator;
import cn.zjnu.a02.m3decision.RadarService;
import cn.zjnu.a02.m5execution.TaskService;

/**
 * M6 反馈层 —— 成长轨迹可视化与动态调整（FR-6.1 / FR-6.2 / FR-6.3）。
 *
 * <p>面板数据**一次聚合返回 4 类图表**（雷达 / 进度环 / 成长曲线 / 活跃热力图），
 * 避免前端 4 次往返与口径不一致（架构文档 §5.2）。
 */
@Service
public class GrowthService {

    private static final Logger log = LoggerFactory.getLogger(GrowthService.class);

    private final Db db;
    private final JsonUtils json;
    private final RadarService radarService;
    private final TaskService taskService;

    public GrowthService(Db db, JsonUtils json, RadarService radarService, TaskService taskService) {
        this.db = db;
        this.json = json;
        this.radarService = radarService;
        this.taskService = taskService;
    }

    // ------------------------------------------------------------------
    // 面板聚合（FR-6.1）
    // ------------------------------------------------------------------

    public Map<String, Object> panel(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();

        // ① 雷达图（含上一版本号，前端据此触发动画）
        int latestVersion = radarService.latestVersion(userId);
        if (latestVersion > 0) {
            Map<String, Object> radar = radarService.latest(userId);
            radar.put("previousVersion", latestVersion > 1 ? latestVersion - 1 : null);
            result.put("radar", radar);
        } else {
            result.put("radar", Map.of("version", 0, "dimensions", List.of(), "previousVersion", null));
        }

        // ② 进度环
        Map<String, Object> stats = taskService.stats(userId);
        Map<String, Object> progress = new LinkedHashMap<>(stats);
        progress.put("label", "学习任务完成率");
        result.put("progress", progress);

        // ③ 成长曲线：按周取当周最新的画像平均分
        result.put("trend", trend(userId));

        // ④ 活跃热力图
        List<Map<String, Object>> cells = taskService.heatmap(userId, 84);
        Map<String, Object> heatmap = new LinkedHashMap<>();
        heatmap.put("from", cells.isEmpty() ? null : cells.get(0).get("date"));
        heatmap.put("to", cells.isEmpty() ? null : cells.get(cells.size() - 1).get("date"));
        heatmap.put("unit", "minutes");
        heatmap.put("cells", cells);
        result.put("heatmap", heatmap);

        // ⑤ 阶段对比摘要 + 偏离提示
        if (latestVersion > 1) {
            result.put("compare", radarService.compare(userId, latestVersion - 1, latestVersion));
        } else {
            result.put("compare", Map.of("summary", "完成一次模拟训练或打卡后，这里会出现前后版本对比。"));
        }
        result.put("alerts", db.camelList("SELECT id, type, message, created_at FROM deviation_alert"
                + " WHERE user_id=? AND resolved=0 ORDER BY id DESC LIMIT 5", userId));
        result.put("versions", radarService.versions(userId));
        result.put("disclaimer", "AI 生成内容仅供参考，请结合自身情况核实。能力分值由可审计公式计算，非模型编造。");
        return result;
    }

    /** 成长曲线：每个自然周取该周最后一次画像的平均分。 */
    private Map<String, Object> trend(long userId) {
        List<Map<String, Object>> rows = db.list("SELECT r.version, r.created_at,"
                + " (SELECT ROUND(AVG(weighted_score),1) FROM radar_dimension_score d WHERE d.radar_id = r.id) AS avg_score"
                + " FROM competency_radar r WHERE r.user_id=? ORDER BY r.version", userId);
        Map<String, Map<String, Object>> byWeek = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate date = parseDate(row.get("created_at"));
            if (date == null) {
                continue;
            }
            LocalDate monday = date.with(DayOfWeek.MONDAY);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", "W" + monday.getMonthValue() + "/" + monday.getDayOfMonth());
            point.put("date", monday.toString());
            point.put("value", row.get("avg_score"));
            point.put("version", row.get("version"));
            byWeek.put(monday.toString(), point);
        }
        List<Map<String, Object>> points = new ArrayList<>(byWeek.values());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unit", "week");
        result.put("metric", "能力画像平均分");
        result.put("points", points);
        result.put("note", points.isEmpty()
                ? "完成能力测评后即可看到成长曲线"
                : "每个点代表该周最后一次画像计算的 8 维平均分");
        return result;
    }

    // ------------------------------------------------------------------
    // 快照与偏离检测（FR-6.2 / FR-6.3）
    // ------------------------------------------------------------------

    /** 每次画像生成都落一份成长快照，支撑任意两时点对比。 */
    @EventListener
    @Transactional
    public void onRadarVersionCreated(Events.RadarVersionCreated event) {
        try {
            snapshot(event.userId(), event.radarId(), event.version(), "因 " + event.triggeredBy() + " 触发");
            detectDeviation(event.userId(), event.radarId(), event.version());
        } catch (Exception e) {
            log.warn("成长快照写入失败 userId={} err={}", event.userId(), e.getMessage());
        }
    }

    @Transactional
    public long snapshot(long userId, long radarId, int version, String remark) {
        List<Map<String, Object>> dims = db.list("SELECT dimension_key, weighted_score"
                + " FROM radar_dimension_score WHERE radar_id=?", radarId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        double sum = 0;
        for (Map<String, Object> dim : dims) {
            snapshot.put(String.valueOf(dim.get("dimension_key")),
                    ((Number) dim.get("weighted_score")).doubleValue());
            sum += ((Number) dim.get("weighted_score")).doubleValue();
        }
        double avg = dims.isEmpty() ? 0 : Math.round(sum / dims.size() * 10) / 10.0;
        snapshot.put("avgScore", avg);
        snapshot.put("version", version);

        long recordId = db.insert("INSERT INTO growth_record(user_id, radar_id, snapshot_json, remark)"
                + " VALUES (?,?,?,?)", userId, radarId, json.stringify(snapshot), remark);
        db.insert("INSERT INTO growth_snapshot_metric(record_id, metric_key, metric_value) VALUES (?,?,?)",
                recordId, "avgScore", avg);
        for (Map<String, Object> dim : dims) {
            db.insert("INSERT INTO growth_snapshot_metric(record_id, metric_key, metric_value) VALUES (?,?,?)",
                    recordId, String.valueOf(dim.get("dimension_key")),
                    ((Number) dim.get("weighted_score")).doubleValue());
        }
        return recordId;
    }

    /** 偏离检测（FR-6.3）：触发条件明确、可复现。 */
    private void detectDeviation(long userId, long radarId, int version) {
        List<Map<String, Object>> records = db.list("SELECT record_id, metric_value FROM growth_snapshot_metric"
                + " WHERE metric_key='avgScore' AND record_id IN (SELECT id FROM growth_record WHERE user_id=?)"
                + " ORDER BY record_id DESC LIMIT 2", userId);
        if (records.size() == 2) {
            double latest = ((Number) records.get(0).get("metric_value")).doubleValue();
            double previous = ((Number) records.get(1).get("metric_value")).doubleValue();
            if (latest < previous - 3.0) {
                db.insert("INSERT INTO deviation_alert(user_id, type, message) VALUES (?,?,?)",
                        userId, "SCORE_DROP",
                        "最新画像平均分（" + latest + "）较上一版本（" + previous
                                + "）回落超过 3 分，建议复盘近期学习投入是否落在目标技能上。");
            }
        }
        long recentCheckins = db.count("SELECT COUNT(*) FROM task_checkin WHERE user_id=?"
                + " AND checkin_date >= ?", userId, java.sql.Date.valueOf(LocalDate.now().minusDays(7)));
        if (recentCheckins == 0 && db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=?", userId) > 0) {
            long openAlerts = db.count("SELECT COUNT(*) FROM deviation_alert WHERE user_id=?"
                    + " AND type='INACTIVE' AND resolved=0", userId);
            if (openAlerts == 0) {
                db.insert("INSERT INTO deviation_alert(user_id, type, message) VALUES (?,?,?)",
                        userId, "INACTIVE",
                        "最近 7 天没有打卡记录，学习节奏可能中断。建议今天完成一个最小单元，重新建立连续性。");
            }
        } else {
            // 条件不再成立时自动关闭历史提示，避免面板长期显示过期告警
            db.update("UPDATE deviation_alert SET resolved=1 WHERE user_id=? AND type='INACTIVE' AND resolved=0",
                    userId);
        }
    }

    public Map<String, Object> compare(long userId, int versionA, int versionB) {
        return radarService.compare(userId, versionA, versionB);
    }

    public List<Map<String, Object>> snapshots(long userId) {
        return db.camelList("SELECT g.id, g.radar_id, g.remark, g.created_at, r.version,"
                + " (SELECT metric_value FROM growth_snapshot_metric m"
                + "   WHERE m.record_id = g.id AND m.metric_key = 'avgScore') AS avg_score"
                + " FROM growth_record g LEFT JOIN competency_radar r ON r.id = g.radar_id"
                + " WHERE g.user_id=? ORDER BY g.id DESC LIMIT 20", userId);
    }

    @Transactional
    public Map<String, Object> resolveAlert(long userId, long alertId) {
        db.update("UPDATE deviation_alert SET resolved=1 WHERE id=? AND user_id=?", alertId, userId);
        return Map.of("alertId", alertId, "resolved", true,
                "message", "已标记为已知悉。系统会在下次画像更新时重新评估。");
    }

    /** 公式说明（与 M3 一致，供成长面板展示「分值从哪来」）。 */
    public Map<String, Object> formula() {
        return Map.of("formulaVersion", RadarCalculator.FORMULA_VERSION,
                "weightTable", RadarCalculator.weightTable(),
                "note", "能力分值由可审计公式计算，AI 只负责语言生成，不参与打分。");
    }

    private static LocalDate parseDate(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(value).substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}
