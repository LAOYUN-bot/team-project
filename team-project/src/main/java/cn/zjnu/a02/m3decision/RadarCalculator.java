package cn.zjnu.a02.m3decision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力雷达计算器（架构文档 §6.5，AD-4）。
 *
 * <p><b>核心主张：雷达分值必须"算出来"，不能"编出来"。</b>
 * 每个维度由若干**可追溯来源**加权归一化得到，缺来源时按剩余权重重归一化，
 * 并在 {@code calcInput} 中留存完整快照，保证任何历史版本都能被复算与审计。
 *
 * <pre>
 *   raw       = Σ(来源分值 × 来源权重) / Σ(来源权重)        // 缺失来源自动重归一化
 *   final     = raw                                          // 首次（ASSESSMENT）直接采用
 *   final     = old × (1 − α) + raw × α                       // 后续版本应用衰减因子 α
 *   gap_i     = max(0, 岗位要求_i − 能力_i)                    // 见 CognitionService
 *   matchRate = 100 × Σ(要求_i × 能力_i/100) / Σ(要求_i)       // 见 MatchService
 * </pre>
 */
public final class RadarCalculator {

    /** 公式版本号：公式变更时递增，旧版本数据仍可按旧公式复算。 */
    public static final String FORMULA_VERSION = "v1";

    /** 维度定义（key → 中文名），与 M1 保持一致。 */
    public static final List<String> DIMENSION_KEYS = List.of(
            "PRO_SKILL", "GEN_ABILITY", "INDUSTRY_KNOW", "PROJECT_EXP",
            "LEARNING_ABILITY", "COMMUNICATION", "TOOL_USAGE", "COLLABORATION");

    public static final Map<String, String> DIMENSION_LABELS = Map.of(
            "PRO_SKILL", "专业技能", "GEN_ABILITY", "通用能力", "INDUSTRY_KNOW", "行业认知",
            "PROJECT_EXP", "项目经验", "LEARNING_ABILITY", "学习力", "COMMUNICATION", "沟通表达",
            "TOOL_USAGE", "工具使用", "COLLABORATION", "协作意识");

    /** 一个维度分值的来源构成。 */
    public record Contribution(String source, double score, double weight) {
    }

    /** 单个维度的计算结果。 */
    public record DimensionOutcome(String key, String label, double rawScore, double finalScore,
                                   double previousScore, boolean decayApplied,
                                   List<Contribution> contributions) {
    }

    /** 一次完整的雷达计算结果（含可审计快照）。 */
    public record RadarOutcome(String formulaVersion, String triggeredBy, double alpha,
                               Map<String, Object> calcInput, List<DimensionOutcome> dimensions) {
    }

    /**
     * 计算输入。
     *
     * @param assessment    测评来源分值（8 维，可空）
     * @param simulation    训练评分来源（已映射到 8 维雷达维度，可空）
     * @param taskCompletion 任务完成率 0-100（可空）
     * @param taskStreak    打卡连续性得分 0-100（可空）
     * @param taskTimeliness 任务时效得分 0-100（可空）
     * @param projectTag    简历/项目标签得分 0-100（可空）
     * @param previous      上一版本分值（可空；为空表示首次生成）
     * @param alpha         衰减因子
     */
    public record RadarInput(Map<String, Double> assessment,
                             Map<String, Double> simulation,
                             Double taskCompletion,
                             Double taskStreak,
                             Double taskTimeliness,
                             Double projectTag,
                             Map<String, Double> previous,
                             double alpha) {
    }

    private RadarCalculator() {
    }

    public static RadarOutcome compute(RadarInput input, String triggeredBy) {
        boolean firstVersion = input.previous() == null || input.previous().isEmpty();
        List<DimensionOutcome> outcomes = new ArrayList<>();

        for (String key : DIMENSION_KEYS) {
            List<Contribution> contributions = new ArrayList<>();

            switch (key) {
                case "PRO_SKILL" -> {
                    add(contributions, "测评", value(input.assessment(), key), 0.4);
                    add(contributions, "训练评分", avgSim(input, "PROBLEM_SOLVING", "AI_LITERACY"), 0.6);
                }
                case "GEN_ABILITY" -> {
                    add(contributions, "测评", value(input.assessment(), key), 0.5);
                    add(contributions, "训练评分", avgSim(input, "INFLUENCING", "CRITICAL_THINKING",
                            "PROBLEM_SOLVING"), 0.5);
                }
                case "INDUSTRY_KNOW" -> {
                    add(contributions, "测评", value(input.assessment(), key), 0.6);
                    add(contributions, "学习任务", input.taskCompletion(), 0.4);
                }
                case "PROJECT_EXP" -> {
                    add(contributions, "简历/项目标签", input.projectTag(), 0.6);
                    add(contributions, "任务产出物", input.taskCompletion(), 0.4);
                }
                case "LEARNING_ABILITY" -> {
                    add(contributions, "任务完成率", input.taskCompletion(), 0.5);
                    add(contributions, "连续打卡", input.taskStreak(), 0.3);
                    add(contributions, "按时完成", input.taskTimeliness(), 0.2);
                }
                case "COMMUNICATION" -> add(contributions, "训练评分", avgSim(input, "COMMUNICATION"), 1.0);
                case "TOOL_USAGE" -> {
                    add(contributions, "测评", value(input.assessment(), key), 0.5);
                    add(contributions, "训练评分", avgSim(input, "AI_LITERACY"), 0.5);
                }
                case "COLLABORATION" -> add(contributions, "训练评分", avgSim(input, "COLLABORATION"), 1.0);
                default -> {
                    // 不应到达
                }
            }

            double weightSum = contributions.stream().mapToDouble(Contribution::weight).sum();
            double raw;
            if (weightSum == 0) {
                // 该维度暂无任何来源：沿用上一版本，首次生成则取中性值 55
                Double prev = input.previous() == null ? null : input.previous().get(key);
                raw = prev == null ? 55.0 : prev;
            } else {
                raw = contributions.stream()
                        .mapToDouble(c -> c.score() * c.weight()).sum() / weightSum;
            }
            raw = clamp(round1(raw));

            Double previous = input.previous() == null ? null : input.previous().get(key);
            boolean decay = !firstVersion && previous != null;
            double finalScore = decay
                    ? clamp(round1(previous * (1 - input.alpha()) + raw * input.alpha()))
                    : raw;

            outcomes.add(new DimensionOutcome(key, DIMENSION_LABELS.get(key), raw, finalScore,
                    previous == null ? raw : previous, decay, contributions));
        }

        Map<String, Object> calcInput = new LinkedHashMap<>();
        calcInput.put("assessment", input.assessment());
        calcInput.put("simulation", input.simulation());
        calcInput.put("taskCompletion", input.taskCompletion());
        calcInput.put("taskStreak", input.taskStreak());
        calcInput.put("taskTimeliness", input.taskTimeliness());
        calcInput.put("projectTag", input.projectTag());
        calcInput.put("previous", input.previous());
        calcInput.put("alpha", input.alpha());
        calcInput.put("formulaVersion", FORMULA_VERSION);
        calcInput.put("weights", weightTable());
        calcInput.put("note", "本快照记录本次计算的全部输入与权重，任何历史版本均可按此复算（AD-4 可审计要求）");

        return new RadarOutcome(FORMULA_VERSION, triggeredBy, input.alpha(), calcInput, outcomes);
    }

    /** 权重表（同时用于界面展示「分值从哪来」）。 */
    public static Map<String, Object> weightTable() {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("PRO_SKILL", Map.of("测评", 0.4, "训练评分", 0.6));
        table.put("GEN_ABILITY", Map.of("测评", 0.5, "训练评分", 0.5));
        table.put("INDUSTRY_KNOW", Map.of("测评", 0.6, "学习任务", 0.4));
        table.put("PROJECT_EXP", Map.of("简历/项目标签", 0.6, "任务产出物", 0.4));
        table.put("LEARNING_ABILITY", Map.of("任务完成率", 0.5, "连续打卡", 0.3, "按时完成", 0.2));
        table.put("COMMUNICATION", Map.of("训练评分", 1.0));
        table.put("TOOL_USAGE", Map.of("测评", 0.5, "训练评分", 0.5));
        table.put("COLLABORATION", Map.of("训练评分", 1.0));
        return table;
    }

    private static void add(List<Contribution> list, String source, Double score, double weight) {
        if (score != null) {
            list.add(new Contribution(source, clamp(round1(score)), weight));
        }
    }

    private static Double value(Map<String, Double> map, String key) {
        return map == null ? null : map.get(key);
    }

    /** 训练评分维度取平均；全部缺失时返回 null（触发权重重归一化）。 */
    private static Double avgSim(RadarInput input, String... keys) {
        if (input.simulation() == null || input.simulation().isEmpty()) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (String key : keys) {
            Double value = input.simulation().get(key);
            if (value != null) {
                sum += value;
                count++;
            }
        }
        return count == 0 ? null : sum / count;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
