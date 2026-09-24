package cn.zjnu.a02.app.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.m1perception.PerceptionService;
import cn.zjnu.a02.m1perception.mapper.UserMapper;
import cn.zjnu.a02.m3decision.CareerPathService;
import cn.zjnu.a02.m4generation.AgentChatService;
import cn.zjnu.a02.m5execution.SimulationService;
import cn.zjnu.a02.m5execution.TaskService;
import cn.zjnu.a02.m6feedback.CompanionService;

/**
 * 演示数据播种器。
 *
 * <p>目的：让系统**一启动就有一条完整的闭环数据**，答辩现场不必从零点半小时的按钮。
 * 播种内容严格走真实业务链路（注册 → 建档 → 测评 → 生成路径 → 拆解单元 → 任务 →
 * 打卡 → 训练评分 → 画像迭代），因此种子数据本身就是闭环跑通的证据。
 *
 * <p>演示账号：{@code demo / demo1234}（普通用户）、{@code admin / admin1234}（管理员，用于 RBAC 演示）。
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String DEMO_USER = "demo";
    private static final String DEMO_PASSWORD = "demo1234";
    private static final String ADMIN_USER = "admin";
    private static final String ADMIN_PASSWORD = "admin1234";

    private final Db db;
    private final PerceptionService perception;
    private final CareerPathService careerPath;
    private final TaskService taskService;
    private final SimulationService simulation;
    private final CompanionService companion;
    private final AgentChatService agentChat;
    private final UserMapper userMapper;

    public DemoDataSeeder(Db db, PerceptionService perception, CareerPathService careerPath,
                          TaskService taskService, SimulationService simulation,
                          CompanionService companion, AgentChatService agentChat,
                          UserMapper userMapper) {
        this.db = db;
        this.perception = perception;
        this.careerPath = careerPath;
        this.taskService = taskService;
        this.simulation = simulation;
        this.companion = companion;
        this.agentChat = agentChat;
        this.userMapper = userMapper;
    }

    /** 在闭包表重算之后执行，保证依赖顺序。 */
    @Order(20)
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (db.count("SELECT COUNT(*) FROM sys_user") > 0) {
            log.info("已存在用户数据，跳过演示数据播种");
            return;
        }
        try {
            seedAdmin();
            long userId = seedDemoUser();
            log.info("演示数据播种完成：demo/demo1234（userId={}），admin/admin1234", userId);
        } catch (Exception e) {
            log.warn("演示数据播种失败（不影响手工操作）：{}", e.getMessage(), e);
        }
    }

    private void seedAdmin() {
        var registered = perception.register(ADMIN_USER, ADMIN_PASSWORD, "系统管理员");
        long adminId = ((Number) registered.get("userId")).longValue();
        // 通过 Mapper 提权（演示 RBAC：管理员接口需 ADMIN 角色）
        userMapper.updateRole(adminId, "ADMIN");
    }

    private long seedDemoUser() {
        var registered = perception.register(DEMO_USER, DEMO_PASSWORD, "演示同学");
        long userId = ((Number) registered.get("userId")).longValue();

        // ① 感知：基础信息 + 隐私授权
        perception.saveConsent(userId, true, true);
        perception.saveProfile(userId, new PerceptionService.ProfileInput(
                "本科", "计算机科学与技术", "大三", 0, "学生",
                1001L, "后端开发,云原生,AI 应用", 10));

        // ② 感知 → 决策：能力测评（自动生成雷达 v1 与差距分析）
        perception.submitAssessment(userId, buildAnswers());

        // 简历标签（影响「项目经验」维度来源）
        db.insert("INSERT INTO resume_extract_tag(user_id, tag, category, confirmed) VALUES (?,?,?,1)",
                userId, "Spring Boot", "SKILL");
        db.insert("INSERT INTO resume_extract_tag(user_id, tag, category, confirmed) VALUES (?,?,?,1)",
                userId, "MySQL 数据库", "SKILL");
        db.insert("INSERT INTO resume_extract_tag(user_id, tag, category, confirmed) VALUES (?,?,?,1)",
                userId, "Git 版本控制", "SKILL");

        // ③ 决策：生成 3–5 年路径（发布事件 → ④ 生成层自动拆解学习单元）
        careerPath.generate(userId, 4);

        // ⑤ 执行：先播种历史打卡（保证「学习力」维度有真实来源），再从学习单元生成任务
        seedCheckins(userId);
        taskService.createFromUnits(userId, 6);
        List<Object> taskIds = new ArrayList<>();
        for (var row : db.list("SELECT id FROM learning_task WHERE user_id=? ORDER BY id LIMIT 4", userId)) {
            taskIds.add(row.get("id"));
        }
        for (Object taskId : taskIds) {
            // 任务状态变更会广播 TaskChanged → M3 自动生成画像新版本（D-3 画像动态迭代）
            taskService.updateStatus(userId, ((Number) taskId).longValue(), "DONE");
        }

        // 留一个任务处于「进行中」，让看板有真实状态分布（H2/MySQL 均不支持 UPDATE ... LIMIT）
        var nextTodo = db.one("SELECT id FROM learning_task WHERE user_id=? AND status='TODO' ORDER BY id LIMIT 1",
                userId);
        if (nextTodo != null) {
            db.update("UPDATE learning_task SET status='DOING' WHERE id=?", nextTodo.get("id"));
        }

        // ⑦ 执行：完成一次模拟训练并评分 → 触发画像新版本 + 成长快照
        seedSimulation(userId);

        // ⑧ 生成层：播种一轮 AI 伙伴对话，让「历史会话」也有内容（FR-4.4）
        seedAgentChat(userId);

        // ⑨ 反馈：激励与偏离检查
        companion.state(userId);
        db.update("INSERT INTO behavior_event(user_id, event_type, payload) VALUES (?,?,?)",
                userId, "DEMO_SEEDED", "闭环演示数据已就绪");
        return userId;
    }

    /** 测评答案：故意做出维度差异，便于演示「短板识别」。 */
    private List<PerceptionService.AnswerInput> buildAnswers() {
        int[] values = {
                3, 3,   // 专业技能 60
                4, 4,   // 通用能力 80
                3, 2,   // 行业认知 50
                2, 2,   // 项目经验 40
                4, 5,   // 学习力 90
                4, 4,   // 沟通表达 80
                4, 3,   // 工具使用 70
                4, 5    // 协作意识 90
        };
        List<PerceptionService.AnswerInput> answers = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            answers.add(new PerceptionService.AnswerInput(i + 1, values[i]));
        }
        return answers;
    }

    private void seedCheckins(long userId) {
        LocalDate today = LocalDate.now();
        // 最近 12 天中打卡 9 天，且包含最近连续 5 天，构造真实感的连续性
        int[] pattern = {0, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1};
        for (int i = 0; i < pattern.length; i++) {
            if (pattern[i] == 0) {
                continue;
            }
            LocalDate date = today.minusDays(pattern.length - 1L - i);
            db.update("INSERT INTO task_checkin(user_id, checkin_date, minutes, note) VALUES (?,?,?,?)",
                    userId, java.sql.Date.valueOf(date), 30 + (i % 4) * 15,
                    i % 3 == 0 ? "完成学习单元并整理了笔记" : null);
        }
    }

    private void seedSimulation(long userId) {
        var session = simulation.startSession(userId, 1L);
        long sessionId = ((Number) session.get("sessionId")).longValue();
        List<String> messages = List.of(
                "林工你好，先说背景：需求方希望本迭代加一个导出功能，我确认过是面向运营的月报场景。"
                        + "我理解你们排期也紧，所以我先做了影响分析——涉及 2 个接口和 1 张表，预计 3 人天。",
                "我准备了两个方案：方案一是本迭代做简化版导出，1 人天，先满足月报；"
                        + "方案二是完整版放到下个迭代，3 人天。我倾向方案一，风险可控。你觉得哪个更合适？",
                "如果走方案一，前端和测试的工作我可以接过来，你只需要确认表结构调整会不会影响现有查询。"
                        + "截止时间建议定在本周五，这样下周一就能联调。",
                "明白，那我们就按方案一走。我会把结论同步给需求方，并写成三条动作项："
                        + "你确认表结构、我负责接口和前端、周五前完成联调。");
        for (String message : messages) {
            simulation.sendMessage(userId, sessionId, message, chunk -> {
                // 播种阶段不推送流式内容
            });
        }
        simulation.evaluate(userId, sessionId);
    }

    /** 播种一轮「成长伙伴」对话，用于演示角色化对话与历史会话（FR-4.4）。 */
    private void seedAgentChat(long userId) {
        Long sessionId = null;
        for (String message : List.of(
                "我最近有点迷茫，不知道该先学什么，感觉什么都得学。",
                "那我这周具体该做什么？")) {
            var result = agentChat.chatStream(userId, sessionId, message, chunk -> {
                // 播种阶段不需要流式推送
            });
            sessionId = ((Number) result.get("sessionId")).longValue();
        }
    }
}
