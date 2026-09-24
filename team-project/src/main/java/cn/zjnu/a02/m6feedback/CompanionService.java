package cn.zjnu.a02.m6feedback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.m4generation.AgentChatService;
import cn.zjnu.a02.m5execution.TaskService;

/**
 * M6 反馈层 —— 情感陪伴与目标监督激励（FR-6.4）。
 *
 * <p>这是本项目的**创新差异点**（难题 P3：人机信任与长期陪伴）。
 * 激励文案由 AI 生成，但**事实来自数据库**（打卡天数、完成数、里程碑），
 * 避免出现「你很棒」这类无依据的空洞鼓励——这也是防 AI 幻觉在体验层的体现。
 */
@Service
public class CompanionService {

    private final Db db;
    private final AiService aiService;
    private final TaskService taskService;
    private final AgentChatService agentChat;

    public CompanionService(Db db, AiService aiService, TaskService taskService, AgentChatService agentChat) {
        this.db = db;
        this.aiService = aiService;
        this.taskService = taskService;
        this.agentChat = agentChat;
    }

    /** 陪伴状态：打卡、里程碑、下一个动作、激励语。 */
    public Map<String, Object> state(long userId) {
        Map<String, Object> stats = taskService.stats(userId);
        int streak = ((Number) stats.getOrDefault("streakDays", 0)).intValue();
        int done = ((Number) stats.getOrDefault("doneTasks", 0)).intValue();
        int total = ((Number) stats.getOrDefault("totalTasks", 0)).intValue();

        List<Map<String, Object>> milestones = milestones(userId, streak, done);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("streakDays", streak);
        context.put("doneTasks", done);
        context.put("totalTasks", total);
        context.put("nextAction", agentChat.nextAction(userId));
        context.put("milestone", milestones.isEmpty() ? "" : String.valueOf(milestones.get(0).get("title")));

        var result = aiService.structured("incentive", userId, context, null);
        Map<String, Object> data = result.data();
        String message = String.valueOf(data.getOrDefault("message", "今天完成一个最小单元就好。"));
        String nextAction = String.valueOf(data.getOrDefault("nextAction", agentChat.nextAction(userId)));

        db.insert("INSERT INTO incentive_log(user_id, type, content) VALUES (?,?,?)",
                userId, String.valueOf(data.getOrDefault("toneTag", "NUDGE")), message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", message);
        response.put("toneTag", data.getOrDefault("toneTag", "NUDGE"));
        response.put("nextAction", nextAction);
        response.put("streakDays", streak);
        response.put("stats", stats);
        response.put("milestones", milestones);
        response.put("facts", data.getOrDefault("facts", Map.of(
                "streakDays", streak, "doneTasks", done, "totalTasks", total)));
        response.put("degraded", result.degraded());
        response.put("promptVersion", result.promptVersion());
        response.put("aiGenerated", true);
        response.put("disclaimer", "以上鼓励语由 AI 生成，其中事实数据来自你的真实学习记录。");
        return response;
    }

    /** 里程碑判定：规则明确、可复现。 */
    private List<Map<String, Object>> milestones(long userId, int streak, int done) {
        List<Map<String, Object>> milestones = new ArrayList<>();
        if (streak >= 7) {
            milestones.add(Map.of("code", "STREAK_7", "title", "连续打卡 7 天", "achieved", true));
        }
        if (streak >= 3) {
            milestones.add(Map.of("code", "STREAK_3", "title", "连续打卡 3 天", "achieved", true));
        }
        if (done >= 10) {
            milestones.add(Map.of("code", "TASK_10", "title", "完成 10 个学习任务", "achieved", true));
        }
        long simulations = db.count("SELECT COUNT(*) FROM evaluation WHERE user_id=?", userId);
        if (simulations >= 1) {
            milestones.add(Map.of("code", "SIM_FIRST", "title", "完成首个场景模拟训练", "achieved", true));
        }
        long radarVersions = db.count("SELECT COUNT(*) FROM competency_radar WHERE user_id=?", userId);
        if (radarVersions >= 3) {
            milestones.add(Map.of("code", "PORTRAIT_3", "title", "能力画像迭代 3 个版本", "achieved", true));
        }
        if (milestones.isEmpty()) {
            milestones.add(Map.of("code", "START", "title", "迈出第一步：完成建档与测评", "achieved", false));
        }
        return milestones;
    }

    public List<Map<String, Object>> incentives(long userId) {
        return db.camelList("SELECT id, type, content, created_at FROM incentive_log"
                + " WHERE user_id=? ORDER BY id DESC LIMIT 10", userId);
    }

    @Transactional
    public Map<String, Object> logMilestone(long userId, String title) {
        db.insert("INSERT INTO incentive_log(user_id, type, content) VALUES (?,?,?)",
                userId, "MILESTONE", "达成里程碑：" + title);
        return Map.of("logged", true, "title", title);
    }

    /** 情绪感知回应（FR-6.4）：由 AI 伙伴对话入口统一处理，这里只提供情绪标签判定规则。 */
    public Map<String, Object> emotionProbe(String text) {
        String t = text == null ? "" : text;
        String tag = "NEUTRAL";
        if (t.matches(".*(焦虑|迷茫|压力|害怕|担心|崩溃|坚持不下去).*")) {
            tag = "ANXIOUS";
        } else if (t.matches(".*(开心|顺利|拿到|通过|完成|进步).*")) {
            tag = "POSITIVE";
        } else if (t.matches(".*(累|疲惫|不想|放弃).*")) {
            tag = "FATIGUED";
        }
        Map<String, String> responses = Map.of(
                "ANXIOUS", "先停一下：这种状态很正常。我们把目标切到今天这一步，做完再说下一步。",
                "POSITIVE", "这个进展值得记一笔。把它写进你的成长记录，它是你能力画像的一部分。",
                "FATIGUED", "累了就先休息，连续性比强度更重要——哪怕今天只做 15 分钟也算保持住了。",
                "NEUTRAL", "我在。说说具体卡在哪一步，我们一起拆。");
        return Map.of("tag", tag, "suggestedResponse", responses.get(tag),
                "note", "情绪标签由规则判定，回复文案由 AI 伙伴生成，避免机械化应答。");
    }
}
