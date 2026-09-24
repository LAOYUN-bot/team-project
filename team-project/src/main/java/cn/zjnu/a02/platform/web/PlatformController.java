package cn.zjnu.a02.platform.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.ai.adapter.TboxAiAdapter;
import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.platform.security.AuthContext;
import cn.zjnu.a02.platform.security.RequireRole;

/**
 * 平台可观测与合规接口（NFR-4 可追溯 / FR-7.5 AI 伦理）。
 *
 * <p>为答辩与企业专项材料提供**运行时证据**：
 * AI 生成日志（提示词版本、重试、降级）、审计日志、提示词仓库、系统配置、隐私处理说明。
 */
@RestController
@RequestMapping("/api/platform")
public class PlatformController {

    private final Db db;
    private final AiService aiService;
    private final TboxAiAdapter tboxAdapter;

    public PlatformController(Db db, AiService aiService, TboxAiAdapter tboxAdapter) {
        this.db = db;
        this.aiService = aiService;
        this.tboxAdapter = tboxAdapter;
    }

    /** AI 运行状态与平台适配度证据（企业专项材料①的运行时部分）。 */
    @GetMapping("/ai-status")
    public ApiResponse<Map<String, Object>> aiStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", aiService.provider());
        result.put("tboxConfigured", aiService.tboxConfigured());
        result.put("fallbackEnabled", true);
        result.put("tboxConfig", tboxAdapter.describeConfig());
        result.put("degradePolicy", "主通道失败时自动降级为本地模板，并在响应中标记 degraded=true，"
                + "前端展示「AI 已降级为本地模板」提示，演示不中断");
        result.put("models", List.of("百灵（规划）", "通义千问", "DeepSeek"));
        return ApiResponse.ok(result);
    }

    /** AI 生成日志：每次生成的场景、提示词版本、耗时、重试次数、是否降级。 */
    @GetMapping("/ai-logs")
    public ApiResponse<List<Map<String, Object>>> aiLogs(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(db.camelList("SELECT id, scene, provider, model, prompt_version, latency_ms,"
                + " retry_count, degraded, created_at FROM ai_generation_log"
                + " WHERE user_id=? OR user_id IS NULL ORDER BY id DESC LIMIT ?",
                AuthContext.userId(), Math.max(1, Math.min(100, limit))));
    }

    /** 提示词仓库与版本（架构文档 §7.3「提示词即行为代码」）。 */
    @GetMapping("/prompts")
    public ApiResponse<Map<String, Object>> prompts() {
        return ApiResponse.ok(aiService.promptCatalog());
    }

    /** 审计日志（仅管理员，RBAC 演示 403 的场景）。 */
    @GetMapping("/audit-logs")
    @RequireRole({"ADMIN"})
    public ApiResponse<List<Map<String, Object>>> auditLogs(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(db.camelList("SELECT id, user_id, action, target, detail, ip, created_at"
                + " FROM audit_log ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(100, limit))));
    }

    /** 系统配置（雷达权重、衰减因子等，可截图给评委看）。 */
    @GetMapping("/config")
    public ApiResponse<List<Map<String, Object>>> config() {
        return ApiResponse.ok(db.camelList("SELECT cfg_key, cfg_value, remark FROM sys_config ORDER BY cfg_key"));
    }

    /** 隐私与 AI 伦理处理说明（FR-7.5 / NFR-3 / NFR-4）。 */
    @GetMapping("/privacy")
    public ApiResponse<Map<String, Object>> privacy() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collectScope", List.of(
                "采集：账号、学历专业、目标岗位、能力自评、学习行为",
                "不采集：身份证号、银行卡、生物特征、简历库爬取数据"));
        result.put("measures", List.of(
                "口令 BCrypt(cost=10) 入库",
                "手机号/邮箱 AES-GCM 加密列存储，接口只回掩码",
                "传模型前执行脱敏管道（姓名→用户A、手机→掩码、院校→泛化）",
                "AI 产出必须经 Schema 校验与业务规则二次校验后才落库",
                "每条 AI 消息标记 is_ai_generated，界面固定展示 AI 标识",
                "提供数据导出（GET /api/users/me/data）与级联删除（DELETE /api/users/me/data）"));
        result.put("ethics", List.of(
                "算法偏见：提示词显式禁止因性别/年龄/地域/院校产生差异建议",
                "防过度依赖：关键建议附理由与来源，界面提示「仅供参考」",
                "透明性：对话界面固定 AI 标识，能力边界写入人设提示词",
                "责任归属：所有生成写 ai_generation_log，可追溯模型与提示词版本",
                "合规数据：仅使用公开资料改写与自构造模拟数据"));
        result.put("userConsent", db.camel(db.one("SELECT * FROM user_consent WHERE user_id=?",
                AuthContext.userId())));
        return ApiResponse.ok(result);
    }

    /** 闭环总览（答辩开场用：六步闭环的数据量证据）。 */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        long userId = AuthContext.userId();
        Map<String, Object> steps = new LinkedHashMap<>();
        steps.put("M1 感知", Map.of(
                "assessments", db.count("SELECT COUNT(*) FROM assessment_record WHERE user_id=?", userId),
                "profileReady", db.count("SELECT COUNT(*) FROM user_profile WHERE user_id=?", userId) > 0));
        steps.put("M2 认知", Map.of(
                "knowledgeNodes", db.count("SELECT COUNT(*) FROM kg_entity"),
                "relations", db.count("SELECT COUNT(*) FROM kg_relation"),
                "trendReports", db.count("SELECT COUNT(*) FROM trend_report WHERE user_id=?", userId)));
        steps.put("M3 决策", Map.of(
                "radarVersions", db.count("SELECT COUNT(*) FROM competency_radar WHERE user_id=?", userId),
                "gapReports", db.count("SELECT COUNT(*) FROM gap_analysis WHERE user_id=?", userId),
                "paths", db.count("SELECT COUNT(*) FROM career_path WHERE user_id=?", userId)));
        steps.put("M4 生成", Map.of(
                "learningUnits", db.count("SELECT COUNT(*) FROM learning_unit WHERE user_id=?", userId),
                "chatMessages", db.count("SELECT COUNT(*) FROM chat_message WHERE session_id IN"
                        + " (SELECT id FROM chat_session WHERE user_id=?)", userId)));
        steps.put("M5 执行", Map.of(
                "tasks", db.count("SELECT COUNT(*) FROM learning_task WHERE user_id=?", userId),
                "simulationSessions", db.count("SELECT COUNT(*) FROM simulation_session WHERE user_id=?", userId),
                "evaluations", db.count("SELECT COUNT(*) FROM evaluation WHERE user_id=?", userId),
                "mcpCalls", db.count("SELECT COUNT(*) FROM mcp_tool_log")));
        steps.put("M6 反馈", Map.of(
                "growthSnapshots", db.count("SELECT COUNT(*) FROM growth_record WHERE user_id=?", userId),
                "incentives", db.count("SELECT COUNT(*) FROM incentive_log WHERE user_id=?", userId),
                "alerts", db.count("SELECT COUNT(*) FROM deviation_alert WHERE user_id=?", userId)));
        return ApiResponse.ok(Map.of("steps", steps,
                "note", "以上数字均为真实数据库统计，可用于验证「感知→反馈」闭环确实跑通。"));
    }
}
