package cn.zjnu.a02.m5execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.event.Events;
import cn.zjnu.a02.common.port.AiChatPort.Message;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.m4generation.AgentChatService;
import cn.zjnu.a02.platform.audit.AuditService;

/**
 * M5 执行层 —— 职场场景模拟训练与自动评分（FR-5.2 / FR-5.3）。
 *
 * <p>这是全项目**最有说服力的一段演示**（架构文档 §3.4）：
 * 完成一次训练 → 逐维评分 → 触发 M3 重算 → 雷达图产生新版本且可与旧版本对比，
 * 即「画像动态迭代」（难题 P1）的可视化物证。
 *
 * <p>三个场景（赛题 T3 要求 ≥3 个）：远程协作冲突处理、AI 辅助办公、跨岗位沟通。
 */
@Service
public class SimulationService {

    private static final int MIN_TURNS_FOR_EVALUATION = 3;

    private final Db db;
    private final AiService aiService;
    private final JsonUtils json;
    private final AgentChatService agentChat;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;

    public SimulationService(Db db, AiService aiService, JsonUtils json, AgentChatService agentChat,
                             AuditService audit, ApplicationEventPublisher publisher) {
        this.db = db;
        this.aiService = aiService;
        this.json = json;
        this.agentChat = agentChat;
        this.audit = audit;
        this.publisher = publisher;
    }

    // ------------------------------------------------------------------
    // 场景（FR-5.2）
    // ------------------------------------------------------------------

    public List<Map<String, Object>> scenarios() {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (Map<String, Object> row : db.list("SELECT * FROM simulation_scenario ORDER BY id")) {
            Map<String, Object> item = db.camel(row);
            item.put("rubric", db.camelList("SELECT dim_key, name, weight, description"
                    + " FROM rubric_dimension WHERE scenario_id=? ORDER BY id", row.get("id")));
            item.put("dimensionCount", db.count("SELECT COUNT(*) FROM rubric_dimension WHERE scenario_id=?",
                    row.get("id")));
            scenarios.add(item);
        }
        return scenarios;
    }

    // ------------------------------------------------------------------
    // 会话（FR-5.2）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> startSession(long userId, long scenarioId) {
        Map<String, Object> scenario = db.one("SELECT * FROM simulation_scenario WHERE id=?", scenarioId);
        if (scenario == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "训练场景不存在");
        }
        // 每个场景只保留一个进行中的会话，重复进入则复用
        Map<String, Object> ongoing = db.one("SELECT id, chat_session_id FROM simulation_session"
                + " WHERE user_id=? AND scenario_id=? AND status='ONGOING' ORDER BY id DESC LIMIT 1",
                userId, scenarioId);
        if (ongoing != null) {
            long sessionId = ((Number) ongoing.get("id")).longValue();
            return detail(userId, sessionId);
        }

        long chatSessionId = db.insert("INSERT INTO chat_session(user_id, kind, title) VALUES (?,?,?)",
                userId, "SIMULATION", "模拟训练：" + scenario.get("name"));
        long sessionId = db.insert("INSERT INTO simulation_session(user_id, scenario_id, chat_session_id,"
                        + " status, turn_count) VALUES (?,?,?,'ONGOING',0)",
                userId, scenarioId, chatSessionId);
        db.insert("INSERT INTO chat_message(session_id, role, content, is_ai_generated) VALUES (?,?,?,1)",
                chatSessionId, "assistant", scenario.get("opening"));
        audit.record(userId, "SIM_START", "simulation_session#" + sessionId,
                "开始训练：" + scenario.get("name"));
        return detail(userId, sessionId);
    }

    /** 多轮对话（SSE 流式的数据侧）。 */
    @Transactional
    public Map<String, Object> sendMessage(long userId, long sessionId, String message,
                                           Consumer<String> onToken) {
        Map<String, Object> session = ownedSession(userId, sessionId);
        if (!"ONGOING".equals(String.valueOf(session.get("status")))) {
            throw new BizException(ErrorCode.SESSION_FINISHED);
        }
        if (message == null || message.isBlank()) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "消息内容不能为空");
        }
        if (message.length() > 2000) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "单条消息请控制在 2000 字以内");
        }
        long chatSessionId = ((Number) session.get("chat_session_id")).longValue();
        db.insert("INSERT INTO chat_message(session_id, role, content, is_ai_generated) VALUES (?,?,?,0)",
                chatSessionId, "user", message);
        int turn = ((Number) session.get("turn_count")).intValue() + 1;
        db.update("UPDATE simulation_session SET turn_count=? WHERE id=?", turn, sessionId);

        List<Message> history = history(chatSessionId);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("roleCard", session.get("role_card"));
        context.put("scenarioDesc", session.get("description"));
        context.put("scenarioCode", session.get("code"));
        context.put("scenarioName", session.get("name"));
        context.put("turn", turn);
        context.put("userNickname", agentChat.buildContext(userId).get("nickname"));

        StringBuilder buffer = new StringBuilder();
        aiService.streamRolePlay(userId, context, history, chunk -> {
            buffer.append(chunk);
            onToken.accept(chunk);
        });
        db.insert("INSERT INTO chat_message(session_id, role, content, is_ai_generated) VALUES (?,?,?,1)",
                chatSessionId, "assistant", buffer.toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("turn", turn);
        result.put("reply", buffer.toString());
        result.put("canEvaluate", turn >= MIN_TURNS_FOR_EVALUATION);
        return result;
    }

    /** 历史消息（供角色扮演作为上下文）。 */
    public List<Message> history(long chatSessionId) {
        List<Map<String, Object>> rows = db.list("SELECT role, content FROM chat_message"
                + " WHERE session_id=? ORDER BY id DESC LIMIT 12", chatSessionId);
        List<Message> messages = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            messages.add(new Message(String.valueOf(rows.get(i).get("role")),
                    String.valueOf(rows.get(i).get("content"))));
        }
        return messages;
    }

    // ------------------------------------------------------------------
    // 自动评分（FR-5.3）
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, Object> evaluate(long userId, long sessionId) {
        Map<String, Object> session = ownedSession(userId, sessionId);
        long chatSessionId = ((Number) session.get("chat_session_id")).longValue();
        long scenarioId = ((Number) session.get("scenario_id")).longValue();

        List<Map<String, Object>> messages = db.camelList(
                "SELECT role, content FROM chat_message WHERE session_id=? ORDER BY id", chatSessionId);
        long userTurns = messages.stream().filter(m -> "user".equals(m.get("role"))).count();
        if (userTurns < MIN_TURNS_FOR_EVALUATION) {
            throw new BizException(ErrorCode.INSUFFICIENT_TURNS,
                    "至少完成 " + MIN_TURNS_FOR_EVALUATION + " 轮发言才能生成评分（当前 " + userTurns + " 轮）");
        }
        long existing = db.count("SELECT COUNT(*) FROM evaluation WHERE session_id=?", sessionId);
        if (existing > 0) {
            return evaluationOf(sessionId);
        }

        List<Map<String, Object>> rubric = db.camelList(
                "SELECT dim_key, name, weight, description FROM rubric_dimension"
                        + " WHERE scenario_id=? ORDER BY id", scenarioId);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("scenarioName", session.get("name"));
        context.put("scenarioCode", session.get("code"));
        context.put("rubric", rubric);
        context.put("messages", messages);

        var result = aiService.structured("score", userId, context, null);
        Map<String, Object> data = result.data();

        double overall = toDouble(data.get("overallScore"), 60);
        String level = String.valueOf(data.getOrDefault("level", "合格"));
        String summary = String.valueOf(data.getOrDefault("summary", ""));
        long evaluationId = db.insert("INSERT INTO evaluation(session_id, user_id, overall_score, level,"
                        + " summary, suggestions_json, prompt_version, degraded) VALUES (?,?,?,?,?,?,?,?)",
                sessionId, userId, overall, level, summary,
                json.stringify(data.getOrDefault("suggestions", List.of())),
                result.promptVersion(), result.degraded() ? 1 : 0);

        Object dims = data.get("dimensions");
        if (dims instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String key = String.valueOf(m.get("key"));
                    double weight = rubric.stream()
                            .filter(r -> key.equals(String.valueOf(r.get("dimKey"))))
                            .map(r -> toDouble(r.get("weight"), 1))
                            .findFirst().orElse(1.0);
                    db.insert("INSERT INTO evaluation_score(evaluation_id, dim_key, name, score, weight, comment)"
                                    + " VALUES (?,?,?,?,?,?)",
                            evaluationId, key, String.valueOf(m.get("name")),
                            toDouble(m.get("score"), 60), weight, String.valueOf(m.get("comment")));
                }
            }
        }

        db.update("UPDATE simulation_session SET status='FINISHED', ended_at=CURRENT_TIMESTAMP WHERE id=?",
                sessionId);
        audit.record(userId, "SIM_EVALUATE", "simulation_session#" + sessionId,
                "训练评分 " + overall + "（" + level + "），触发画像重算");

        // 闭环关键：训练评分 → 回写能力画像（D-3）
        publisher.publishEvent(new Events.EvaluationCompleted(userId, evaluationId,
                String.valueOf(session.get("code")), overall));

        Map<String, Object> response = new LinkedHashMap<>(evaluationOf(sessionId));
        response.put("disclaimer", "AI 评分仅反映本次对话中的表达证据，不代表你的综合能力。");
        response.put("radarUpdated", true);
        response.put("nextStep", "已根据本次训练重算能力画像，可前往「成长面板」对比前后版本。");
        return response;
    }

    public Map<String, Object> evaluationOf(long sessionId) {
        Map<String, Object> evaluation = db.one("SELECT * FROM evaluation WHERE session_id=?", sessionId);
        if (evaluation == null) {
            return Map.of("exists", false);
        }
        long evaluationId = ((Number) evaluation.get("id")).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", true);
        result.put("evaluationId", evaluationId);
        result.put("overallScore", evaluation.get("overall_score"));
        result.put("level", evaluation.get("level"));
        result.put("summary", evaluation.get("summary"));
        result.put("suggestions", json.parseList(String.valueOf(evaluation.get("suggestions_json"))));
        result.put("degraded", ((Number) evaluation.get("degraded")).intValue() == 1);
        result.put("promptVersion", evaluation.get("prompt_version"));
        result.put("createdAt", String.valueOf(evaluation.get("created_at")));
        result.put("dimensions", db.camelList("SELECT dim_key, name, score, weight, comment"
                + " FROM evaluation_score WHERE evaluation_id=? ORDER BY id", evaluationId));
        return result;
    }

    public List<Map<String, Object>> sessions(long userId) {
        return db.camelList("SELECT s.id, s.status, s.turn_count, s.started_at, s.ended_at,"
                + " sc.name AS scenario_name, sc.code AS scenario_code,"
                + " e.overall_score, e.level"
                + " FROM simulation_session s"
                + " JOIN simulation_scenario sc ON sc.id = s.scenario_id"
                + " LEFT JOIN evaluation e ON e.session_id = s.id"
                + " WHERE s.user_id=? ORDER BY s.id DESC", userId);
    }

    public Map<String, Object> detail(long userId, long sessionId) {
        Map<String, Object> session = ownedSession(userId, sessionId);
        long chatSessionId = ((Number) session.get("chat_session_id")).longValue();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("scenarioId", session.get("scenario_id"));
        result.put("scenarioName", session.get("name"));
        result.put("scenarioCode", session.get("code"));
        result.put("status", session.get("status"));
        result.put("turnCount", session.get("turn_count"));
        result.put("canEvaluate", ((Number) session.get("turn_count")).intValue() >= MIN_TURNS_FOR_EVALUATION);
        result.put("minTurns", MIN_TURNS_FOR_EVALUATION);
        result.put("rubric", db.camelList("SELECT dim_key, name, weight, description FROM rubric_dimension"
                + " WHERE scenario_id=? ORDER BY id", session.get("scenario_id")));
        result.put("messages", db.camelList("SELECT id, role, content, is_ai_generated, created_at"
                + " FROM chat_message WHERE session_id=? ORDER BY id", chatSessionId));
        result.put("evaluation", evaluationOf(sessionId));
        return result;
    }

    private Map<String, Object> ownedSession(long userId, long sessionId) {
        Map<String, Object> session = db.one("SELECT s.*, sc.code, sc.name, sc.description, sc.role_card"
                + " FROM simulation_session s JOIN simulation_scenario sc ON sc.id = s.scenario_id"
                + " WHERE s.id=? AND s.user_id=?", sessionId, userId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "训练会话不存在或不属于当前用户");
        }
        return session;
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
}
