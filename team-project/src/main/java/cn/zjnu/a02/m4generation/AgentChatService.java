package cn.zjnu.a02.m4generation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.port.AiChatPort.Message;
import cn.zjnu.a02.m1perception.PerceptionService;

/**
 * M4 生成层 —— 角色化自然语言对话（FR-4.4）。
 *
 * <p>AI 伙伴人设固定（提示词 persona_v3），上下文由后端**结构化注入**而不是让模型猜：
 * 目标岗位、能力短板、学习进度、连续打卡、下一个待办动作。
 * 每条助手消息都标记 {@code is_ai_generated=1}（NFR-4 界面 AI 标识的数据基础）。
 */
@Service
public class AgentChatService {

    private static final int MAX_CONTEXT_MESSAGES = 12;

    private final Db db;
    private final AiService aiService;
    private final PerceptionService perception;

    public AgentChatService(Db db, AiService aiService, PerceptionService perception) {
        this.db = db;
        this.aiService = aiService;
        this.perception = perception;
    }

    /** 获取或创建会话。 */
    @Transactional
    public long ensureSession(long userId, Long sessionId) {
        if (sessionId != null) {
            Map<String, Object> row = db.one("SELECT id FROM chat_session WHERE id=? AND user_id=?",
                    sessionId, userId);
            if (row != null) {
                return sessionId;
            }
        }
        return db.insert("INSERT INTO chat_session(user_id, kind, title) VALUES (?,?,?)",
                userId, "AGENT", "与成长伙伴「灯塔」的对话");
    }

    /** 构建注入给模型的用户上下文（结构化，不让模型猜）。 */
    public Map<String, Object> buildContext(long userId) {
        Map<String, Object> ctx = new LinkedHashMap<>(perception.userContext(userId));
        ctx.put("streakDays", (int) streakDays(userId));
        ctx.put("progressPercent", (int) progressPercent(userId));
        ctx.put("weakDimensions", weakDimensions(userId));
        ctx.put("nextAction", nextAction(userId));
        ctx.put("nickname", String.valueOf(ctx.getOrDefault("nickname", "同学")));
        ctx.put("targetJobName", String.valueOf(ctx.getOrDefault("targetJobName", "你的目标岗位")));
        return ctx;
    }

    /**
     * 流式对话：把用户消息落库 → 流式生成 → 助手消息落库。
     *
     * @param onToken 每个增量片段回调（用于 SSE 推送）
     * @return 结果摘要（会话 id、助手消息 id、是否降级）
     */
    @Transactional
    public Map<String, Object> chatStream(long userId, Long sessionId, String userMessage,
                                          Consumer<String> onToken) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        }
        if (userMessage.length() > 2000) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "单条消息请控制在 2000 字以内");
        }
        long chatSessionId = ensureSession(userId, sessionId);
        db.insert("INSERT INTO chat_message(session_id, role, content, is_ai_generated) VALUES (?,?,?,0)",
                chatSessionId, "user", userMessage);

        List<Message> history = history(chatSessionId);
        StringBuilder buffer = new StringBuilder();
        aiService.stream("persona", userId, buildContext(userId), history, null, chunk -> {
            buffer.append(chunk);
            onToken.accept(chunk);
        });

        long messageId = db.insert("INSERT INTO chat_message(session_id, role, content, is_ai_generated)"
                        + " VALUES (?,?,?,1)",
                chatSessionId, "assistant", buffer.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", chatSessionId);
        result.put("messageId", messageId);
        result.put("content", buffer.toString());
        result.put("provider", aiService.provider());
        result.put("remoteConfigured", aiService.remoteConfigured());
        result.put("disclaimer", "以上内容由 AI 生成，仅供参考，请结合自身情况核实。");
        return result;
    }

    public List<Message> history(long sessionId) {
        List<Map<String, Object>> rows = db.list(
                "SELECT role, content FROM chat_message WHERE session_id=? ORDER BY id DESC LIMIT ?",
                sessionId, MAX_CONTEXT_MESSAGES);
        List<Message> messages = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            messages.add(new Message(String.valueOf(rows.get(i).get("role")),
                    String.valueOf(rows.get(i).get("content"))));
        }
        return messages;
    }

    public List<Map<String, Object>> sessions(long userId) {
        return db.camelList("SELECT s.id, s.title, s.created_at,"
                + " (SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) AS message_count"
                + " FROM chat_session s WHERE s.user_id=? AND s.kind='AGENT' ORDER BY s.id DESC", userId);
    }

    public List<Map<String, Object>> messages(long userId, long sessionId) {
        Map<String, Object> session = db.one("SELECT id FROM chat_session WHERE id=? AND user_id=?",
                sessionId, userId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return db.camelList("SELECT id, role, content, is_ai_generated, created_at"
                + " FROM chat_message WHERE session_id=? ORDER BY id", sessionId);
    }

    // ------------------------------------------------------------------
    // 上下文取数
    // ------------------------------------------------------------------

    public long streakDays(long userId) {
        List<Map<String, Object>> rows = db.list(
                "SELECT checkin_date FROM task_checkin WHERE user_id=? ORDER BY checkin_date DESC LIMIT 60",
                userId);
        if (rows.isEmpty()) {
            return 0;
        }
        long streak = 0;
        java.time.LocalDate expected = java.time.LocalDate.now();
        for (Map<String, Object> row : rows) {
            String text = String.valueOf(row.get("checkin_date"));
            java.time.LocalDate date;
            try {
                date = java.time.LocalDate.parse(text.substring(0, 10));
            } catch (Exception e) {
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

    public double progressPercent(long userId) {
        long total = db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=?", userId);
        if (total == 0) {
            return 0;
        }
        return Math.round(db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=? AND status='DONE'", userId)
                * 1000.0 / total) / 10.0;
    }

    /** 能力短板：雷达分值最低的 3 个维度。 */
    public List<String> weakDimensions(long userId) {
        List<Map<String, Object>> rows = db.list("SELECT d.dimension_key, d.weighted_score"
                + " FROM radar_dimension_score d"
                + " WHERE d.radar_id = (SELECT id FROM competency_radar WHERE user_id=?"
                + "                     ORDER BY version DESC LIMIT 1)"
                + " ORDER BY d.weighted_score ASC LIMIT 3", userId);
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = String.valueOf(row.get("dimension_key"));
            labels.add(cn.zjnu.a02.m3decision.RadarCalculator.DIMENSION_LABELS.getOrDefault(key, key));
        }
        return labels;
    }

    public String nextAction(long userId) {
        Map<String, Object> task = db.one("SELECT title FROM learning_task WHERE user_id=? AND status<>'DONE'"
                + " ORDER BY (due_date IS NULL), due_date, id LIMIT 1", userId);
        if (task != null) {
            return String.valueOf(task.get("title"));
        }
        Map<String, Object> unit = db.one("SELECT title FROM learning_unit WHERE user_id=? AND status='TODO'"
                + " ORDER BY id LIMIT 1", userId);
        if (unit != null) {
            return String.valueOf(unit.get("title"));
        }
        return "完成一次模拟训练并查看评分";
    }
}
