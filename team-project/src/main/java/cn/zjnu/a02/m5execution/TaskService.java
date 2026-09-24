package cn.zjnu.a02.m5execution;

import java.time.LocalDate;
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
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.m5execution.entity.LearningTask;
import cn.zjnu.a02.m5execution.mapper.LearningTaskMapper;

/**
 * M5 执行层 —— 学习任务管理与提醒（FR-5.1）。
 *
 * <p>任务状态变更会落库并广播 {@link Events.TaskChanged}，
 * 由 M3 按「任务完成率 / 连续打卡 / 按时完成」重算学习力维度，
 * 形成「执行 → 反馈 → 画像更新」的闭环。
 */
@Service
public class TaskService {

    private final Db db;
    private final LearningTaskMapper taskMapper;
    private final JsonUtils json;
    private final ApplicationEventPublisher publisher;

    public TaskService(Db db, LearningTaskMapper taskMapper, JsonUtils json,
                       ApplicationEventPublisher publisher) {
        this.db = db;
        this.taskMapper = taskMapper;
        this.json = json;
        this.publisher = publisher;
    }

    /**
     * 任务列表（课程实验一「任务表 CRUD」的查询入口）。
     *
     * <p>实体查询走 MyBatis Mapper（含单元 / 阶段名关联），
     * 输出前统一转成 camelCase 视图，避免实体字段名与前端契约耦合。
     */
    public List<Map<String, Object>> tasks(long userId, String status) {
        List<LearningTask> rows = taskMapper.selectByUser(userId, status);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LearningTask task : rows) {
            result.add(toView(task));
        }
        return result;
    }

    private Map<String, Object> toView(LearningTask task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("userId", task.getUserId());
        view.put("unitId", task.getUnitId());
        view.put("title", task.getTitle());
        view.put("dueDate", task.getDueDate() == null ? null : task.getDueDate().toString());
        view.put("status", task.getStatus());
        view.put("doneAt", task.getDoneAt() == null ? null : task.getDoneAt().toString());
        view.put("unitTitle", task.getUnitTitle());
        view.put("stageName", task.getStageName());
        return view;
    }

    /** 从已拆解的学习单元生成学习任务（执行层的入口动作）。 */
    @Transactional
    public Map<String, Object> createFromUnits(long userId, Integer limit) {
        int max = limit == null ? 8 : Math.max(1, Math.min(20, limit));
        List<Map<String, Object>> units = db.list(
                "SELECT id, title, est_hours FROM learning_unit WHERE user_id=? ORDER BY id LIMIT ?",
                userId, max);
        if (units.isEmpty()) {
            throw new BizException(ErrorCode.NO_PATH, "尚未拆解学习单元，请先生成职业路径");
        }
        long existing = db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=?", userId);
        if (existing > 0) {
            return Map.of("created", 0, "message", "已存在学习任务，未重复生成", "stats", stats(userId));
        }
        int created = 0;
        LocalDate start = LocalDate.now();
        for (int i = 0; i < units.size(); i++) {
            Map<String, Object> unit = units.get(i);
            LearningTask task = new LearningTask();
            task.setUserId(userId);
            task.setUnitId(((Number) unit.get("id")).longValue());
            task.setTitle("完成学习单元：" + unit.get("title"));
            task.setDueDate(start.plusWeeks(i));
            taskMapper.insert(task);
            created++;
        }
        return Map.of("created", created, "stats", stats(userId));
    }

    @Transactional
    public Map<String, Object> updateStatus(long userId, long taskId, String status) {
        if (status == null || !List.of("TODO", "DOING", "DONE").contains(status.toUpperCase())) {
            throw new BizException(ErrorCode.UNSUPPORTED_VALUE, "状态取值须为 TODO / DOING / DONE");
        }
        LearningTask task = taskMapper.selectByIdAndUser(taskId, userId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在或不属于当前用户");
        }
        String normalized = status.toUpperCase();
        taskMapper.updateStatus(taskId, normalized, "DONE".equals(normalized));
        db.update("INSERT INTO behavior_event(user_id, event_type, payload) VALUES (?,?,?)",
                userId, "TASK_STATUS", "taskId=" + taskId + ",status=" + normalized);
        publisher.publishEvent(new Events.TaskChanged(userId, "STATUS_" + normalized));
        return Map.of("taskId", taskId, "status", normalized,
                "unit", task.getTitle(), "stats", stats(userId));
    }

    /** 每日打卡：同一自然日重复打卡视为更新学习时长（唯一约束保证幂等）。 */
    @Transactional
    public Map<String, Object> checkin(long userId, Integer minutes, String note) {
        LocalDate today = LocalDate.now();
        int mins = minutes == null ? 30 : Math.max(1, Math.min(720, minutes));
        long exists = db.count("SELECT COUNT(*) FROM task_checkin WHERE user_id=? AND checkin_date=?",
                userId, java.sql.Date.valueOf(today));
        if (exists > 0) {
            db.update("UPDATE task_checkin SET minutes=?, note=? WHERE user_id=? AND checkin_date=?",
                    mins, note, userId, java.sql.Date.valueOf(today));
        } else {
            db.insert("INSERT INTO task_checkin(user_id, checkin_date, minutes, note) VALUES (?,?,?,?)",
                    userId, java.sql.Date.valueOf(today), mins, note);
        }
        publisher.publishEvent(new Events.TaskChanged(userId, "CHECKIN"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkinDate", today.toString());
        result.put("minutes", mins);
        result.put("stats", stats(userId));
        result.put("message", "打卡成功。连续打卡是「学习力」维度的重要来源，会真实影响你的能力画像。");
        return result;
    }

    /** 任务与打卡统计（成长面板的进度环数据源）。任务计数走 Mapper，打卡统计走聚合查询。 */
    public Map<String, Object> stats(long userId) {
        long total = taskMapper.countByUser(userId);
        long done = taskMapper.countByStatus(userId, "DONE");
        long doing = taskMapper.countByStatus(userId, "DOING");
        long overdue = taskMapper.countOverdue(userId);
        long checkinDays = db.count("SELECT COUNT(*) FROM task_checkin WHERE user_id=?", userId);
        int totalMinutes = 0;
        Map<String, Object> sum = db.one("SELECT COALESCE(SUM(minutes),0) AS m FROM task_checkin WHERE user_id=?",
                userId);
        if (sum != null && sum.get("m") != null) {
            totalMinutes = ((Number) sum.get("m")).intValue();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTasks", total);
        result.put("doneTasks", done);
        result.put("doingTasks", doing);
        result.put("todoTasks", Math.max(0, total - done - doing));
        result.put("overdueTasks", overdue);
        result.put("percent", total == 0 ? 0 : Math.round(done * 1000.0 / total) / 10.0);
        result.put("checkinDays", checkinDays);
        result.put("totalMinutes", totalMinutes);
        result.put("streakDays", streak(userId));
        return result;
    }

    /** 连续打卡天数。 */
    public long streak(long userId) {
        List<Map<String, Object>> rows = db.list(
                "SELECT checkin_date FROM task_checkin WHERE user_id=? ORDER BY checkin_date DESC LIMIT 90",
                userId);
        if (rows.isEmpty()) {
            return 0;
        }
        long streak = 0;
        LocalDate expected = LocalDate.now();
        for (Map<String, Object> row : rows) {
            LocalDate date = toDate(row.get("checkin_date"));
            if (date == null) {
                continue;
            }
            if (date.equals(expected)) {
                streak++;
                expected = expected.minusDays(1);
            } else if (streak == 0 && date.equals(expected.minusDays(1))) {
                streak++;
                expected = date.minusDays(1);
            } else {
                break;
            }
        }
        return streak;
    }

    /** 活跃热力图数据（FR-6.1 的第 4 类图表）。 */
    public List<Map<String, Object>> heatmap(long userId, int days) {
        int span = Math.max(7, Math.min(180, days));
        LocalDate from = LocalDate.now().minusDays(span - 1L);
        List<Map<String, Object>> cells = new ArrayList<>();
        Map<String, Integer> minutes = new LinkedHashMap<>();
        for (Map<String, Object> row : db.list("SELECT checkin_date, minutes FROM task_checkin"
                + " WHERE user_id=? AND checkin_date >= ?", userId, java.sql.Date.valueOf(from))) {
            LocalDate date = toDate(row.get("checkin_date"));
            if (date != null) {
                minutes.put(date.toString(), ((Number) row.get("minutes")).intValue());
            }
        }
        for (int i = 0; i < span; i++) {
            LocalDate date = from.plusDays(i);
            int value = minutes.getOrDefault(date.toString(), 0);
            cells.add(Map.of("date", date.toString(), "value", value,
                    "level", value == 0 ? 0 : value < 30 ? 1 : value < 60 ? 2 : value < 120 ? 3 : 4));
        }
        return cells;
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof LocalDate d) {
            return d;
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
