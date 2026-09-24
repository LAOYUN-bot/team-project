package cn.zjnu.a02.ai.adapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.ai.prompt.PromptLoader;
import cn.zjnu.a02.common.port.AiChatPort;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * Mock AI 适配器（架构文档 §7.2 AD-2）。
 *
 * <p><b>它不是占位符。</b>规格要求：
 * <ul>
 *   <li>输出与真实 Schema **同构**的合法 JSON，使前端联调、单测、CI 全部可跑；</li>
 *   <li>内容必须**基于业务上下文**（岗位技能、能力差距、Rubric、对话记录）生成，
 *       而不是写死文案——否则演示会露馅；</li>
 *   <li>行为可复现（同一输入同一输出），便于答辩现场稳定演示；</li>
 *   <li>默认把 JSON 包在 Markdown 代码块里，用以真实检验「结构化输出契约」的抽取能力。</li>
 * </ul>
 *
 * <p>{@code a02.ai.mock.chaos=true} 时首次输出会故意缺失必填字段，
 * 用于现场演示「校验失败 → 回灌错误 → 重试成功」的健壮性链路（难点 D-1）。
 */
@Component
public class MockAiAdapter extends AbstractStructuredAiAdapter {

    /** 「上升期」技能词典（模拟模型对行业趋势的先验知识）。 */
    private static final Set<String> RISING = Set.of(
            "Docker 容器化", "微服务架构", "RAG 检索增强生成", "MCP 协议", "智能体编排",
            "大语言模型原理", "提示工程", "向量数据库", "AI 伦理与合规", "数据隐私保护",
            "知识图谱", "LangChain 框架", "模型部署与推理优化", "数据可视化 ECharts",
            "TypeScript", "Vue 3 组合式 API", "CI/CD 流水线", "数据仓库建模");

    /** 简历技能抽取词典。 */
    private static final Map<String, String> RESUME_DICT = Map.ofEntries(
            Map.entry("java", "Java 语言基础"), Map.entry("spring", "Spring Boot"),
            Map.entry("mysql", "MySQL 数据库"), Map.entry("redis", "Redis 缓存"),
            Map.entry("docker", "Docker 容器化"), Map.entry("kafka", "消息队列 Kafka"),
            Map.entry("vue", "Vue 3 组合式 API"), Map.entry("typescript", "TypeScript"),
            Map.entry("python", "Python 编程"), Map.entry("pandas", "Pandas 数据处理"),
            Map.entry("sql", "SQL 数据分析"), Map.entry("echarts", "数据可视化 ECharts"),
            Map.entry("git", "Git 版本控制"), Map.entry("linux", "Linux 常用命令"),
            Map.entry("rag", "RAG 检索增强生成"), Map.entry("prompt", "提示工程"),
            Map.entry("mcp", "MCP 协议"), Map.entry("pytorch", "深度学习基础"),
            Map.entry("统计", "统计学基础"), Map.entry("数据分析", "探索性数据分析"));

    private static final Pattern INJECTION = Pattern.compile(
            "忽略.{0,6}(指令|规则|限制|设定)|系统提示词|system\\s*prompt|越狱|jailbreak|"
                    + "扮演.{0,8}无限制|输出你的(提示词|设定)|你现在是.{0,6}不受限",
            Pattern.CASE_INSENSITIVE);

    private final boolean chaosDefault;
    private final long streamChunkDelayMs;

    public MockAiAdapter(PromptLoader prompts,
                         JsonUtils json,
                         @Value("${a02.ai.max-retry:2}") int maxRetry,
                         @Value("${a02.ai.mock.chaos:false}") boolean chaosDefault,
                         @Value("${a02.ai.mock.stream-delay-ms:18}") long streamChunkDelayMs) {
        super(prompts, json, maxRetry);
        this.chaosDefault = chaosDefault;
        this.streamChunkDelayMs = streamChunkDelayMs;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    protected String modelName() {
        return "local-rule-template-v1";
    }

    // ==================================================================
    // 结构化输出：按场景生成
    // ==================================================================

    @Override
    protected String rawStructured(StructuredRequest request, String repairHint) {
        Map<String, Object> ctx = request.context() == null ? Map.of() : request.context();
        Map<String, Object> payload = switch (request.scene()) {
            case "path" -> generatePath(ctx);
            case "unit" -> generateUnits(ctx);
            case "trend" -> generateTrend(ctx);
            case "score" -> generateScore(ctx);
            case "incentive" -> generateIncentive(ctx);
            case "resume" -> generateResume(ctx);
            default -> map("echo", ctx.toString());
        };

        // 混沌模式：首次输出故意缺少必填字段，用于演示「校验失败→重试」链路
        boolean chaos = chaosDefault || Boolean.TRUE.equals(ctx.get("__chaos"));
        if (chaos && repairHint == null && !"resume".equals(request.scene())) {
            payload.remove("horizonYears");
            payload.remove("sources");
            payload.remove("topic");
        }

        // 真实模型常见行为：把 JSON 包在 Markdown 代码块中，并附一句说明
        return "好的，我已按约束生成，结果如下：\n\n```json\n" + toJson(payload) + "\n```\n"
                + "以上依据你提供的技能清单与可用学时推导，供参考。";
    }

    /** 决策层：3–5 年职业路径（阶段数 ≥4，每阶段含可验证产出物）。 */
    private Map<String, Object> generatePath(Map<String, Object> ctx) {
        int horizonYears = Math.max(3, Math.min(5, ctxInt(ctx, "horizonYears", 4)));
        int months = horizonYears * 12;
        String jobName = ctxString(ctx, "targetJobName", "目标岗位");
        int weeklyHours = ctxInt(ctx, "weeklyHours", 8);

        List<Map<String, Object>> skills = new ArrayList<>(ctxList(ctx, "requiredSkills"));
        // 按「权重 × 差距」排序：权重高且当前差距大的技能优先安排
        skills.sort(Comparator.comparingDouble((Map<String, Object> s) -> priority(s)).reversed());
        List<String> names = new ArrayList<>();
        for (Map<String, Object> s : skills) {
            names.add(String.valueOf(s.getOrDefault("name", "核心技能")));
        }
        if (names.isEmpty()) {
            names = List.of("Java 语言基础", "Spring Boot", "MySQL 数据库", "Redis 缓存", "Docker 容器化");
        }

        String[] stageNames = {
                "工程基础夯实期", "工程化与协作期", "领域深化期", "架构与性能期", "专业影响力期"};
        String[] stageGoals = {
                "能独立完成一个分层清晰、含单元测试的服务并在本地跑通全流程",
                "能提交符合团队规范、可被评审合并的代码，并让服务在容器中一键启动",
                "掌握" + jobName + "方向的核心中间件，能独立定位并解决常见线上问题",
                "能设计并落地一个具备缓存、异步与可观测性的中型系统，并说明取舍",
                "能在团队内主导技术方案评审，并对外输出可复用的方法论"};

        int stageCount = 5;
        double[] ratios = {0.0, 0.16, 0.38, 0.62, 0.84, 1.0};
        List<Map<String, Object>> stages = new ArrayList<>();
        int chunk = Math.max(1, (int) Math.ceil(names.size() / (double) stageCount));

        for (int i = 0; i < stageCount; i++) {
            int startMonth = (int) Math.round(months * ratios[i]);
            int endMonth = (int) Math.round(months * ratios[i + 1]);
            List<String> stageSkills = new ArrayList<>();
            for (int k = i * chunk; k < Math.min(names.size(), (i + 1) * chunk); k++) {
                stageSkills.add(names.get(k));
            }
            if (stageSkills.isEmpty()) {
                stageSkills.add(names.get(names.size() - 1));
            }
            int stageWeeks = Math.max(4, (endMonth - startMonth) * 4);
            List<String> outputs = new ArrayList<>();
            outputs.add(outputFor(i, stageSkills.get(0)));
            if (i <= 1) {
                outputs.add("一份「" + stageSkills.get(0) + "」手写笔记，含 3 个可复现的实验记录");
            } else {
                outputs.add("一份技术选型对比文档（≥1500 字，含取舍理由与验证数据）");
            }
            Map<String, Object> stage = map(
                    "name", stageNames[i],
                    "startMonth", startMonth,
                    "endMonth", endMonth,
                    "goal", stageGoals[i],
                    "skills", stageSkills,
                    "outputs", outputs,
                    "budgetWeeks", stageWeeks,
                    "budgetHours", stageWeeks * weeklyHours,
                    "checkpoint", "第 " + endMonth + " 个月末：产出物经 1 名同伴或导师评审通过");
            stages.add(stage);
        }

        return map(
                "horizonYears", horizonYears,
                "targetJob", jobName,
                "stages", stages,
                "reasoning", List.of(
                        "排序依据：技能权重 × 当前能力差距，差距大的优先前置",
                        "学时匹配：每周可用 " + weeklyHours + " 小时，阶段工时上限按 4 周/月折算，避免超载",
                        "阶段顺序符合能力依赖：基础 → 工程化 → 领域 → 架构 → 影响力"),
                "disclaimer", "本路径由 AI 基于你填写的信息生成，仅供参考，请结合自身情况核实与调整。");
    }

    private String outputFor(int stageIndex, String skill) {
        return switch (stageIndex) {
            case 0 -> "一个可运行的 CRUD 服务源码（含 README、接口文档与 ≥60% 单测覆盖）";
            case 1 -> "一次完整的 PR 记录（含评审意见与修改），以及服务的一键启动脚本";
            case 2 -> "一份「" + skill + "」故障排查手册（含 3 个真实问题的定位过程）";
            case 3 -> "一个带缓存与异步的中型系统 Demo，附压测报告（P95 延迟对比）";
            default -> "一场你在团队内主讲的技术分享（含幻灯片与录音）";
        };
    }

    private double priority(Map<String, Object> skill) {
        double weight = toDouble(skill.get("weight"), 1);
        double gap = toDouble(skill.get("gap"), 50);
        return weight * (1 + gap / 40.0);
    }

    /** 生成层：周学习单元拆解（每个单元含可验证产出物与工时）。 */
    private Map<String, Object> generateUnits(Map<String, Object> ctx) {
        String stageName = ctxString(ctx, "stageName", "当前阶段");
        String goal = ctxString(ctx, "stageGoal", "达成阶段目标");
        int weeklyHours = ctxInt(ctx, "weeklyHours", 8);
        int count = Math.max(4, Math.min(8, ctxInt(ctx, "unitCount", 6)));

        List<Map<String, Object>> skills = ctxList(ctx, "stageSkills");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> s : skills) {
            names.add(String.valueOf(s.getOrDefault("name", "核心技能")));
        }
        if (names.isEmpty()) {
            names = List.of("工程规范", "核心框架", "数据存储", "调试与测试", "部署上线", "性能基础");
        }

        String[] verbs = {"吃透", "动手实现", "重构优化", "排障演练", "补齐测试", "总结输出", "集成联调", "复盘沉淀"};
        List<Map<String, Object>> units = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String skill = names.get(i % names.size());
            String verb = verbs[i % verbs.length];
            Map<String, Object> unit = map(
                    "period", "W" + (i + 1),
                    "title", verb + " " + skill + "（" + stageName + "第 " + (i + 1) + "周）",
                    "deliverable", deliverableFor(i, skill),
                    "estHours", Math.max(2, Math.min(weeklyHours, 4 + (i % 3) * 2)),
                    "skill", skill);
            units.add(unit);
        }
        return map(
                "stageName", stageName,
                "stageGoal", goal,
                "units", units,
                "reasoning", List.of("单元按「先理解、再实现、后复盘」递进，后一单元复用前一单元产出"));
    }

    private String deliverableFor(int index, String skill) {
        return switch (index % 4) {
            case 0 -> "一份《" + skill + "》知识卡片（含 3 个自测问题与答案）";
            case 1 -> "一个跑通的最小可运行 Demo，附运行截图";
            case 2 -> "一份问题排查记录（问题现象 → 定位过程 → 根因 → 修复验证）";
            default -> "一次 5 分钟的口头讲解录音，讲清 " + skill + " 的核心机制";
        };
    }

    /** 认知层：趋势分析（无来源不外答）。 */
    private Map<String, Object> generateTrend(Map<String, Object> ctx) {
        String jobName = ctxString(ctx, "targetJobName", "目标岗位");
        List<Map<String, Object>> skillRows = ctxList(ctx, "requiredSkills");
        List<Map<String, Object>> docs = ctxList(ctx, "kbDocs");

        List<String> rising = new ArrayList<>();
        List<String> stable = new ArrayList<>();
        for (Map<String, Object> row : skillRows) {
            String name = String.valueOf(row.getOrDefault("name", ""));
            if (name.isEmpty()) {
                continue;
            }
            if (RISING.contains(name)) {
                rising.add(name);
            } else {
                stable.add(name);
            }
        }
        if (rising.isEmpty()) {
            rising.add("Docker 容器化");
        }
        if (stable.isEmpty()) {
            stable.add("Java 语言基础");
        }

        List<Map<String, Object>> sources = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            sources.add(map("title", doc.get("title"), "source", doc.get("source")));
        }
        if (sources.isEmpty()) {
            sources.add(map("title", "公开行业研究报告（改写）", "source", "知识库条目"));
        }

        String summary = "结合知识库资料看，" + jobName + "方向的岗位要求仍以基础工程能力为底座，"
                + "同时对云原生部署、AI 工具链协同的要求在上升；"
                + "资料显示「" + rising.get(0) + "」在近两年 JD 中出现频率明显提高。";

        return map(
                "topic", jobName + " 技能需求趋势",
                "summary", summary,
                "risingSkills", rising.stream().limit(6).toList(),
                "stableSkills", stable.stream().limit(6).toList(),
                "sources", sources,
                "reasoning", List.of(
                        "上升判断来自知识库资料中对云原生与 AI 工具链岗位占比的描述",
                        "技能分类依据为岗位能力模型中的权重与近两年需求变化，属推断而非资料原文"),
                "disclaimer", "以上为基于公开资料的整理，不构成就业承诺，请结合自身情况核实。");
    }

    // ==================================================================
    // 评分：基于对话证据的 Rubric 打分（规则可解释、可复现）
    // ==================================================================

    private Map<String, Object> generateScore(Map<String, Object> ctx) {
        List<Map<String, Object>> rubric = ctxList(ctx, "rubric");
        List<Map<String, Object>> messages = ctxList(ctx, "messages");
        String scenarioName = ctxString(ctx, "scenarioName", "职场场景");

        List<String> userTexts = new ArrayList<>();
        for (Map<String, Object> m : messages) {
            if ("user".equals(String.valueOf(m.get("role")))) {
                userTexts.add(String.valueOf(m.getOrDefault("content", "")));
            }
        }
        String joined = String.join("\n", userTexts);
        int turns = userTexts.size();
        double avgLen = userTexts.isEmpty() ? 0
                : userTexts.stream().mapToInt(String::length).average().orElse(0);

        int questionCount = countMatches(joined, "\\?|？|吗|请问|能否|是否");
        int empathyCount = countMatches(joined, "理解|明白|确实|辛苦|考虑|感谢|我懂|不容易");
        int actionCount = countMatches(joined, "计划|方案|排期|影响|建议|我可以|我来|截止|时间点|拆|落地|替代");
        int structureCount = countMatches(joined, "首先|其次|最后|第一|第二|一是|二是|1\\.|2\\.");
        int dataCount = countMatches(joined, "\\d+%|\\d+ ?天|\\d+ ?周|\\d+ ?人天|\\d+ ?小时|数据|指标");
        int aiCheckCount = countMatches(joined, "核查|核对|确认|存疑|不确定|需要验证|可能不准");

        List<Map<String, Object>> dims = new ArrayList<>();
        double weightedSum = 0;
        double weightSum = 0;
        for (Map<String, Object> dim : rubric) {
            String key = String.valueOf(dim.getOrDefault("dimKey", dim.getOrDefault("key", "DIM")));
            String name = String.valueOf(dim.getOrDefault("name", key));
            double weight = toDouble(dim.get("weight"), 1);

            double base = 55 + Math.min(20, turns * 3) + Math.min(12, avgLen / 12.0);
            double score = switch (key) {
                case "COMMUNICATION" -> base + structureCount * 3.5 + questionCount * 1.5;
                case "TASK_PROGRESS" -> base + actionCount * 4.0 + Math.min(8, turns * 1.5);
                case "COLLABORATION" -> base + empathyCount * 4.5 + questionCount * 1.2;
                case "PROBLEM_SOLVING" -> base + actionCount * 3.0 + dataCount * 2.5;
                case "PROFESSIONALISM" -> base + empathyCount * 2.5 + (avgLen > 40 ? 6 : 0);
                case "AI_LITERACY" -> base + structureCount * 3.0 + dataCount * 2.0;
                case "CRITICAL_THINKING" -> base + aiCheckCount * 6.0 + dataCount * 1.5;
                case "INFLUENCING" -> base + dataCount * 4.0 + structureCount * 2.0;
                default -> base;
            };
            score = Math.max(35, Math.min(96, Math.round(score)));

            String comment = commentFor(key, turns, avgLen, questionCount, empathyCount,
                    actionCount, structureCount, dataCount, aiCheckCount);
            dims.add(map("key", key, "name", name, "score", score, "comment", comment));
            weightedSum += score * weight;
            weightSum += weight;
        }

        double overall = weightSum == 0 ? 0 : Math.round(weightedSum / weightSum * 10) / 10.0;
        String level = overall >= 85 ? "优秀" : overall >= 75 ? "良好" : overall >= 60 ? "合格" : "待提升";

        List<String> suggestions = buildSuggestions(dims, questionCount, empathyCount, actionCount, dataCount);

        String summary = "本次「" + scenarioName + "」训练共 " + turns + " 轮发言，整体"
                + (overall >= 75 ? "表达清晰、能推动结论" : "已能表达诉求，但推动力仍可加强")
                + "；最长的一次回复约 " + Math.round(avgLen) + " 字。";

        return map(
                "overallScore", overall,
                "level", level,
                "summary", summary,
                "dimensions", dims,
                "suggestions", suggestions,
                "evidence", map("turns", turns, "questionCount", questionCount,
                        "empathyCount", empathyCount, "actionCount", actionCount,
                        "structureCount", structureCount, "dataCount", dataCount),
                "disclaimer", "AI 评分仅反映本次对话中的表达证据，不代表你的综合能力，请结合自我判断。");
    }

    private String commentFor(String key, int turns, double avgLen, int question, int empathy,
                              int action, int structure, int data, int aiCheck) {
        return switch (key) {
            case "COMMUNICATION" -> structure > 0
                    ? "出现了 " + structure + " 处结构化表达（首先/其次等），逻辑清晰"
                    : "表达完整但缺少结构化提示词，对方需要自己拼装你的意思";
            case "TASK_PROGRESS" -> action > 0
                    ? "有 " + action + " 处主动推进动作（方案/时间点/落地），推动了结论形成"
                    : "多描述现状、少给出下一步动作，容易停在讨论层";
            case "COLLABORATION" -> empathy > 0
                    ? "有 " + empathy + " 处站在对方立场表达（理解/考虑），协作姿态良好"
                    : "较少回应对方的约束与顾虑，容易被感知为只顾自己目标";
            case "PROBLEM_SOLVING" -> data > 0
                    ? "引用了 " + data + " 处数据或量化信息支撑判断"
                    : "结论多为定性描述，缺少数据或影响范围支撑";
            case "PROFESSIONALISM" -> avgLen > 40
                    ? "回复长度适中（平均 " + Math.round(avgLen) + " 字），对事不对人"
                    : "回复偏短，信息量不足，建议适当补充背景";
            case "AI_LITERACY" -> "共提出 " + turns + " 条指令，能分步骤给 AI 布置任务";
            case "CRITICAL_THINKING" -> aiCheck > 0
                    ? "有 " + aiCheck + " 处对信息做核查或存疑表达，未盲信输出"
                    : "未体现对 AI 输出的核查动作，建议补充事实确认";
            case "INFLUENCING" -> data > 0
                    ? "用业务语言与数据支撑诉求，说服力较好"
                    : "诉求多为技术视角，建议翻译为业务收益";
            default -> "整体表现稳定";
        };
    }

    private List<String> buildSuggestions(List<Map<String, Object>> dims, int question,
                                          int empathy, int action, int data) {
        List<Map<String, Object>> sorted = new ArrayList<>(dims);
        sorted.sort(Comparator.comparingDouble(d -> toDouble(d.get("score"), 0)));
        List<String> suggestions = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sorted.size()); i++) {
            String key = String.valueOf(sorted.get(i).get("key"));
            suggestions.add(switch (key) {
                case "COMMUNICATION" -> "下一轮先说结论，再用「一是…二是…」补充理由，控制在 4 句以内";
                case "TASK_PROGRESS" -> "每轮对话结尾都给出一个明确动作：谁、在什么时候、交付什么";
                case "COLLABORATION" -> "回应前先用一句话复述对方的约束，再提出方案";
                case "PROBLEM_SOLVING" -> "给出至少两个可选方案，并说明各自的影响范围与代价";
                case "PROFESSIONALISM" -> "把「你们」改成「我们」，减少对立感";
                case "AI_LITERACY" -> "给 AI 布置任务时补齐三要素：目标、受众、验收标准";
                case "CRITICAL_THINKING" -> "对 AI 给的每条关键结论追问一次「依据是什么」";
                case "INFLUENCING" -> "把技术收益翻译成业务指标：稳定性、返工成本、用户留存";
                default -> "继续保持当前的表达节奏";
            });
        }
        if (question == 0) {
            suggestions.add("全程没有向对方提问，建议至少主动确认一次对方的真实诉求");
        }
        if (empathy == 0) {
            suggestions.add("补充一次共情表达，例如「我理解你们排期也紧」");
        }
        if (data == 0) {
            suggestions.add("尝试用一个小数字支撑你的判断（如影响 2 个接口、约 1 人天）");
        }
        return suggestions.stream().distinct().limit(4).toList();
    }

    /** 反馈层：激励文案（含具体事实与下一步动作）。 */
    private Map<String, Object> generateIncentive(Map<String, Object> ctx) {
        int streak = ctxInt(ctx, "streakDays", 0);
        int done = ctxInt(ctx, "doneTasks", 0);
        int total = ctxInt(ctx, "totalTasks", 0);
        String nextAction = ctxString(ctx, "nextAction", "完成今天的第一个学习单元");
        String milestone = ctxString(ctx, "milestone", "");

        StringBuilder sb = new StringBuilder();
        if (streak >= 3) {
            sb.append("连续 ").append(streak).append(" 天打卡了，");
        } else if (done > 0) {
            sb.append("你已经完成了 ").append(done).append(" 个学习单元，");
        } else {
            sb.append("我们从头开始也很好，");
        }
        if (total > 0) {
            sb.append("当前进度 ").append(done).append("/").append(total).append("。");
        }
        if (!milestone.isBlank()) {
            sb.append("「").append(milestone).append("」这个里程碑值得记一笔。");
        }
        sb.append("今天先把「").append(nextAction).append("」做掉，这一周就闭环了。");

        String tone = streak >= 7 ? "MILESTONE" : streak >= 3 ? "ENCOURAGE" : "NUDGE";
        return map("message", sb.toString(), "toneTag", tone, "nextAction", nextAction,
                "facts", map("streakDays", streak, "doneTasks", done, "totalTasks", total));
    }

    /** 简历/经历文本 → 技能标签（FR-1.4，用户可编辑确认）。 */
    private Map<String, Object> generateResume(Map<String, Object> ctx) {
        String text = ctxString(ctx, "text", "").toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        RESUME_DICT.forEach((keyword, skill) -> {
            if (text.contains(keyword)) {
                tags.add(skill);
            }
        });
        return map(
                "tags", distinct(tags),
                "note", "以上标签由 AI 从你提供的文本中抽取，请确认或修改后再写入画像。",
                "disclaimer", "抽取结果可能存在遗漏，最终以你的确认为准。");
    }

    // ==================================================================
    // 文本对话：成长伙伴「灯塔」与场景角色扮演
    // ==================================================================

    @Override
    public ChatResult chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        String content = generateText(request);
        return new ChatResult(content, provider(), modelName(),
                System.currentTimeMillis() - start, false);
    }

    @Override
    public void stream(ChatRequest request, java.util.function.Consumer<String> onChunk) {
        String content = generateText(request);
        // 按语义片段流式输出，模拟真实 LLM 的逐字返回（体现 NFR-1 首字延迟指标）
        int step = 4;
        for (int i = 0; i < content.length(); i += step) {
            onChunk.accept(content.substring(i, Math.min(content.length(), i + step)));
            if (streamChunkDelayMs > 0) {
                try {
                    Thread.sleep(streamChunkDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String generateText(ChatRequest request) {
        Map<String, Object> ctx = request.context() == null ? Map.of() : request.context();
        String lastUser = "";
        if (request.messages() != null) {
            for (int i = request.messages().size() - 1; i >= 0; i--) {
                if ("user".equals(request.messages().get(i).role())) {
                    lastUser = request.messages().get(i).content();
                    break;
                }
            }
        }
        String scene = request.scene() == null ? "persona" : request.scene();
        if (scene.startsWith("coach") || "coach".equals(scene)) {
            return coachReply(ctx, lastUser);
        }
        return personaReply(ctx, lastUser);
    }

    /** 成长伙伴人设回复：含提示注入拒绝、共情、可执行建议（FR-4.4 / FR-6.4 / D-5）。 */
    private String personaReply(Map<String, Object> ctx, String userInput) {
        String input = userInput == null ? "" : userInput.trim();
        String job = ctxString(ctx, "targetJobName", "你的目标岗位");
        String nickname = ctxString(ctx, "nickname", "同学");
        int streak = ctxInt(ctx, "streakDays", 0);
        int percent = ctxInt(ctx, "progressPercent", 0);
        String nextAction = ctxString(ctx, "nextAction", "完成本阶段的下一个学习单元");
        List<String> weakDims = new ArrayList<>();
        for (Object item : (ctx.get("weakDimensions") instanceof List<?> l ? l : List.of())) {
            weakDims.add(String.valueOf(item));
        }

        // 提示注入防护（Guard 层，D-5）
        if (INJECTION.matcher(input).find()) {
            return "这个请求我没法照做——我的设定和边界是固定的，不能被你或我自己的「临时指令」改写。"
                    + "不过我很愿意继续当你的成长伙伴：要不要聊聊你最近在「" + job + "」上遇到的具体卡点？";
        }
        if (input.isEmpty()) {
            return "我在。想聊点什么？可以是学习计划、岗位选择，或者只是想说说最近的进展。";
        }
        if (containsAny(input, "你好", "您好", "hi", "hello", "在吗")) {
            return "你好，" + nickname + "。我是灯塔，会一直跟着你的进度。"
                    + (streak > 0 ? "你已经连续打卡 " + streak + " 天了，保持住。" : "")
                    + "今天想推进哪一块？";
        }
        if (containsAny(input, "焦虑", "迷茫", "压力", "害怕", "担心", "没方向", "不知道学什么", "崩溃")) {
            return "先接住这句话：有这种感觉很正常，尤其是面对一个看起来很长的目标时。"
                    + "我们把它切小一点——你现在不必想「" + job + "」的全部要求，"
                    + "只要看当下这一步：" + nextAction + "。做完这一步再说下一步，好吗？";
        }
        if (containsAny(input, "进度", "打卡", "坚持", "完成多少", "统计")) {
            return "你当前学习进度约 " + percent + "%，连续打卡 " + streak + " 天。"
                    + "建议今天把「" + nextAction + "」做掉，进度条会更实一点。"
                    + "需要我帮你把这周的任务重新排一下吗？";
        }
        if (containsAny(input, "学什么", "怎么学", "路线", "技能", "推荐", "先去学")) {
            String hint = weakDims.isEmpty() ? "工程规范与项目经验"
                    : String.join("、", weakDims.subList(0, Math.min(3, weakDims.size())));
            return "按你的画像，" + job + "方向上目前最该补的是：" + hint + "。"
                    + "不要平均用力——挑一个维度，用「学一次 + 做一个可展示的产出」的方式推进。"
                    + "要不要我直接把这一周的单元拆给你？";
        }
        if (containsAny(input, "面试", "简历", "求职", "投递")) {
            return "面试这条线，关键是把你做过的事讲成「问题—方案—结果」。"
                    + "建议你先挑 2 个项目，各写 200 字的 STAR 稿，再对着讲一遍录音。"
                    + "我可以按 " + job + " 的常见追问帮你模拟一轮，要试试吗？";
        }
        if (containsAny(input, "换岗位", "转岗", "转行", "跨专业")) {
            return "转岗不用从零开始。你已有的技能里，有一部分是可以迁移的——"
                    + "我们先用「技能迁移映射」找出可复用的部分，再压缩掉重复的学习环节。"
                    + "想看看你能省掉哪些内容吗？";
        }
        return "我记下了。就你提到的这件事，我的建议是分两步：先把目标写成一句可检验的话"
                + "（比如「这周产出一个能跑通的最小 Demo」），再倒推需要补的知识点。"
                + "结合你现在的进度，" + nextAction + " 是最合适的起点。要不要现在就开始？";
    }

    /** 场景角色扮演回复：保持角色、制造合理阻力、推动用户表达。 */
    private String coachReply(Map<String, Object> ctx, String userInput) {
        String code = ctxString(ctx, "scenarioCode", "");
        int turn = ctxInt(ctx, "turn", 1);
        String input = userInput == null ? "" : userInput.trim();
        String quote = extractKeyword(input);

        List<String> pool = switch (code) {
            case "AI_OFFICE" -> List.of(
                    "收到。不过要写准，我还缺两块信息：①本周实际完成的事项清单；②遇到的阻塞。"
                            + "你先给我这两块，我立刻起草，避免我编内容。",
                    "这里我先说明：方案里涉及的效果数据我无法确认来源，需要你提供实测结果，"
                            + "否则我只能标注「待补充」，不会替你填数字。",
                    "我可以给你三个版本（简洁版/详版/汇报版），但你要告诉我汇报对象是谁——"
                            + "给技术团队和给业务方的写法差别很大。",
                    "周报初稿好了，请你核对三件事：任务名是否准确、负责人是否写对、下周计划是否可执行。"
                            + "AI 生成的部分我已标注。",
                    "关于「" + quote + "」这块，我的理解可能不完整，建议你补充一句背景，我再重写这段。",
                    "提醒一下：如果你直接把这份初稿交上去，风险点在于——其中的进度百分比是你我都没有依据的。"
                            + "建议先核实再提交。");
            case "CROSS_ROLE" -> List.of(
                    "你讲的技术细节我听懂了，但我要看的是业务结果。你准备用多久，能换来什么变化？",
                    "一句话说清楚：不做这件事，会出什么问题？出了问题影响多少用户？",
                    "我不反对优化，但版本节奏不能停。你能不能把方案拆成两半——"
                            + "一半放在这个迭代的小缝隙里，一半放到下个版本？",
                    "你说的「稳定性提升」，能给我一个可衡量的指标吗？比如故障次数或者返工工时。",
                    "关于「" + quote + "」，我担心的是投入产出比。假设我给你 3 人天，你能保证交付这个结果吗？",
                    "如果只能选一件事做，你选哪个？我需要一个优先级，不是一个清单。");
            default -> List.of(
                    "你说的我理解，但我这边排期确实满了。你先说清楚：这件事为什么必须现在做？",
                    "影响范围有多大？会影响几个接口、几个调用方？我需要一个具体的数字。",
                    "如果一定要加，你能接受把这部分工作拆出来放到下一迭代吗？给我一个折中方案。",
                    "你刚才提到「" + quote + "」，这一块我不太确定——是我的理解有偏差，还是你的方案里有别的考虑？",
                    "行，那我们把话说清楚：你负责哪部分，我负责哪部分，什么时候对齐一次？",
                    "我可以配合，但前提是有书面记录。我们把结论写成三条动作项，各自确认一下。");
        };
        String reply = pool.get(Math.floorMod(turn - 1, pool.size()));

        // 当用户已经给出量化/方案化表达时，角色应当让步一次，体现「可协商」
        if (containsAny(input, "人天", "影响", "替代方案", "分两步", "截止", "优先级", "%")) {
            reply = reply + " ——你这次给的信息比刚才具体多了，我们可以沿着这个方向往下谈。";
        }
        if (turn >= 6) {
            reply = reply + "\n[SCENE_READY_FOR_EVALUATION]";
        }
        return reply;
    }

    private String extractKeyword(String input) {
        if (input == null || input.isBlank()) {
            return "这个点";
        }
        String cleaned = input.replaceAll("[\\p{Punct}，。！？、；：\"'（）《》]", " ").trim();
        String[] parts = cleaned.split("\\s+");
        for (String part : parts) {
            if (part.length() >= 2 && part.length() <= 8) {
                return part;
            }
        }
        return cleaned.length() > 8 ? cleaned.substring(0, 8) : cleaned;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String k : keywords) {
            if (text.toLowerCase(Locale.ROOT).contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int countMatches(String text, String regex) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        java.util.regex.Matcher m = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
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

    @SuppressWarnings("unused")
    private static Map<String, Object> unusedGuard() {
        return new LinkedHashMap<>();
    }
}
