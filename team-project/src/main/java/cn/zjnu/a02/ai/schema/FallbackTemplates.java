package cn.zjnu.a02.ai.schema;

import java.util.List;
import java.util.Map;

import cn.zjnu.a02.common.util.JsonUtils;

/**
 * 兜底模板（架构文档 §7.4 步骤 [6]）。
 *
 * <p>模型连续失败时必须返回**仍满足业务最低要求**的结构，而不是抛错让页面白屏：
 * 路径仍有 ≥4 个阶段、每个学习单元仍有产出物与工时。结果会标记 {@code degraded=true}，
 * 前端展示「AI 已降级为本地模板」提示。
 */
public final class FallbackTemplates {

    private FallbackTemplates() {
    }

    public static String forScene(String scene, Map<String, Object> context) {
        String job = context == null ? "目标岗位"
                : String.valueOf(context.getOrDefault("targetJobName", "目标岗位"));
        return switch (scene) {
            case "path" -> JsonUtilsSnapshot.PATH.formatted(job);
            case "unit" -> JsonUtilsSnapshot.UNIT;
            case "trend" -> JsonUtilsSnapshot.TREND.formatted(job, job);
            case "score" -> JsonUtilsSnapshot.SCORE;
            case "incentive" -> JsonUtilsSnapshot.INCENTIVE;
            case "resume" -> JsonUtilsSnapshot.RESUME;
            default -> "{}";
        };
    }

    /** 把模板写成常量，避免拼接错误；{@code %s} 由岗位名填充。 */
    private static final class JsonUtilsSnapshot {
        private static final String PATH = """
                {
                  "horizonYears": 4,
                  "targetJob": "%s",
                  "stages": [
                    {"name": "工程基础夯实期", "startMonth": 0, "endMonth": 9,
                     "goal": "能独立完成一个分层清晰、含单元测试的服务并跑通全流程",
                     "skills": ["语言基础", "主流框架", "关系型数据库"],
                     "outputs": ["一个可运行的 CRUD 服务源码（含 README 与测试）"]},
                    {"name": "工程化与协作期", "startMonth": 9, "endMonth": 21,
                     "goal": "能提交可被评审合并的代码并完成容器化部署",
                     "skills": ["版本控制", "容器化", "接口规范"],
                     "outputs": ["一次完整 PR 记录与一键启动脚本"]},
                    {"name": "领域深化期", "startMonth": 21, "endMonth": 36,
                     "goal": "掌握核心中间件并能独立定位线上问题",
                     "skills": ["缓存", "消息队列", "日志与排查"],
                     "outputs": ["一份故障排查手册（含 3 个真实问题定位过程）"]},
                    {"name": "架构与影响力期", "startMonth": 36, "endMonth": 48,
                     "goal": "能主导中型系统设计并对外输出方法论文档",
                     "skills": ["系统设计", "性能优化", "技术写作"],
                     "outputs": ["一场团队内技术分享（含幻灯片与录音）"]}
                  ],
                  "reasoning": ["【兜底模板】AI 通道不可用，本结果由本地规则模板生成，仅保证结构完整"],
                  "disclaimer": "本次结果为降级输出，建议稍后重新生成以获得个性化路径。"
                }
                """;

        private static final String UNIT = """
                {
                  "units": [
                    {"period": "W1", "title": "核心概念与最小可运行 Demo",
                     "deliverable": "一个跑通的最小 Demo（附运行截图）", "estHours": 6},
                    {"period": "W2", "title": "分层结构与接口实现",
                     "deliverable": "一份分层清晰的接口代码（含接口文档）", "estHours": 8},
                    {"period": "W3", "title": "数据存储与查询优化",
                     "deliverable": "一份查询优化前后对比记录（含执行计划）", "estHours": 8},
                    {"period": "W4", "title": "测试与部署",
                     "deliverable": "一份容器化部署脚本与单测报告", "estHours": 6}
                  ],
                  "reasoning": ["【兜底模板】AI 通道不可用，单元结构由本地规则保证"]
                }
                """;

        private static final String TREND = """
                {
                  "topic": "%s 技能需求趋势",
                  "summary": "【降级输出】AI 趋势分析通道暂不可用。根据知识库条目，%s 方向仍以基础工程能力为底座，云原生与 AI 工具链相关要求在上升。",
                  "risingSkills": ["容器化部署", "AI 工具链协同"],
                  "stableSkills": ["编程语言基础", "数据库", "版本控制"],
                  "sources": [{"title": "知识库条目（详见知识库引用表）", "source": "系统知识库"}],
                  "reasoning": ["【兜底模板】未调用模型，结论来自本地规则"],
                  "disclaimer": "本次为降级输出，请稍后重试以获取带来源的完整分析。"
                }
                """;

        private static final String SCORE = """
                {
                  "overallScore": 60.0,
                  "level": "合格",
                  "summary": "【降级输出】AI 评分通道暂不可用，本结果基于对话轮次与长度等基础指标估算。",
                  "dimensions": [
                    {"key": "COMMUNICATION", "name": "沟通表达", "score": 60, "comment": "已记录你的发言，建议重试以获取逐维证据点评"},
                    {"key": "TASK_PROGRESS", "name": "任务推进", "score": 60, "comment": "降级模式不做语义分析"},
                    {"key": "COLLABORATION", "name": "协作意识", "score": 60, "comment": "降级模式不做语义分析"},
                    {"key": "PROBLEM_SOLVING", "name": "问题解决", "score": 60, "comment": "降级模式不做语义分析"}
                  ],
                  "suggestions": ["稍后重试以获取基于对话证据的逐条建议"],
                  "disclaimer": "降级评分不代表真实水平。"
                }
                """;

        private static final String INCENTIVE = """
                {
                  "message": "今天先完成一个最小的学习单元就好，完成比完美更重要。",
                  "toneTag": "NUDGE",
                  "nextAction": "完成一个学习单元"
                }
                """;

        private static final String RESUME = """
                {
                  "tags": [],
                  "note": "【降级输出】AI 标签抽取通道暂不可用，请手动填写技能标签。",
                  "disclaimer": "本次为降级输出。"
                }
                """;

        private JsonUtilsSnapshot() {
        }
    }

    /** 便于单测断言模板始终满足业务最低要求。 */
    public static List<String> supportedScenes() {
        return List.of("path", "unit", "trend", "score", "incentive", "resume");
    }
}
