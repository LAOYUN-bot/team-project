package cn.zjnu.a02.m3decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 能力雷达计算公式单元测试（架构文档 §6.5）。
 *
 * <p>这些用例是「分值算出来而非编出来」这一主张的可执行证据：
 * 权重归一化、缺失来源重归一化、衰减因子、可审计快照四个行为都必须成立。
 */
class RadarCalculatorTest {

    private static Map<String, Double> fullAssessment() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("PRO_SKILL", 60.0);
        m.put("GEN_ABILITY", 80.0);
        m.put("INDUSTRY_KNOW", 50.0);
        m.put("PROJECT_EXP", 40.0);
        m.put("LEARNING_ABILITY", 90.0);
        m.put("COMMUNICATION", 80.0);
        m.put("TOOL_USAGE", 70.0);
        m.put("COLLABORATION", 90.0);
        return m;
    }

    @Test
    @DisplayName("首次生成：单一来源直接采用该来源分值")
    void firstVersionUsesSingleSource() {
        var input = new RadarCalculator.RadarInput(fullAssessment(), null, null, null, null, null, null, 0.35);
        var outcome = RadarCalculator.compute(input, "ASSESSMENT");

        assertEquals(8, outcome.dimensions().size(), "必须输出全部 8 个能力维度");
        assertEquals(60.0, scoreOf(outcome, "PRO_SKILL"), 0.01, "专业技能应等于测评来源分值");
        assertEquals(RadarCalculator.FORMULA_VERSION, outcome.formulaVersion());
        assertTrue(outcome.dimensions().stream().noneMatch(RadarCalculator.DimensionOutcome::decayApplied),
                "首次生成不应应用衰减");
    }

    @Test
    @DisplayName("多来源：按权重加权平均（专业技能 = 测评0.4×60 + 训练0.6×90 = 78）")
    void weightedAverageAcrossSources() {
        Map<String, Double> simulation = new LinkedHashMap<>();
        simulation.put("PROBLEM_SOLVING", 90.0);
        simulation.put("AI_LITERACY", 90.0);

        var input = new RadarCalculator.RadarInput(fullAssessment(), simulation, null, null, null, null, null, 0.35);
        var outcome = RadarCalculator.compute(input, "SIMULATION");

        assertEquals(78.0, scoreOf(outcome, "PRO_SKILL"), 0.01);
    }

    @Test
    @DisplayName("缺失来源：按剩余权重重新归一化，而不是当作 0 分")
    void missingSourceRenormalizes() {
        // 只有训练评分，没有测评 → 专业技能应直接等于训练分（权重被重归一化为 1.0）
        Map<String, Double> simulation = new LinkedHashMap<>();
        simulation.put("PROBLEM_SOLVING", 70.0);
        simulation.put("AI_LITERACY", 70.0);

        var input = new RadarCalculator.RadarInput(null, simulation, null, null, null, null, null, 0.35);
        var outcome = RadarCalculator.compute(input, "SIMULATION");

        assertEquals(70.0, scoreOf(outcome, "PRO_SKILL"), 0.01,
                "缺失测评来源时，专业技能应等于训练评分而非被拉低");
    }

    @Test
    @DisplayName("无任何来源：沿用上一版本分值；首次生成取中性值 55")
    void noSourceFallsBackSafely() {
        var first = RadarCalculator.compute(
                new RadarCalculator.RadarInput(null, null, null, null, null, null, null, 0.35), "ASSESSMENT");
        assertEquals(55.0, scoreOf(first, "COMMUNICATION"), 0.01, "首次且无来源应取中性值");

        Map<String, Double> previous = new LinkedHashMap<>();
        previous.put("COMMUNICATION", 66.0);
        var next = RadarCalculator.compute(
                new RadarCalculator.RadarInput(null, null, null, null, null, null, previous, 0.35), "TASK");
        assertEquals(66.0, scoreOf(next, "COMMUNICATION"), 0.01, "有历史版本时应沿用上一版本分值");
    }

    @Test
    @DisplayName("衰减因子：final = old×(1-α) + raw×α（old=60, raw=80, α=0.5 → 70）")
    void decayAppliedOnLaterVersions() {
        Map<String, Double> assessment = new LinkedHashMap<>();
        assessment.put("PRO_SKILL", 80.0);
        Map<String, Double> previous = new LinkedHashMap<>();
        previous.put("PRO_SKILL", 60.0);

        var outcome = RadarCalculator.compute(
                new RadarCalculator.RadarInput(assessment, null, null, null, null, null, previous, 0.5), "REPLAN");

        var dim = outcome.dimensions().stream()
                .filter(d -> d.key().equals("PRO_SKILL")).findFirst().orElseThrow();
        assertEquals(70.0, dim.finalScore(), 0.01);
        assertEquals(80.0, dim.rawScore(), 0.01, "raw 应保留原始加权值以供审计");
        assertTrue(dim.decayApplied(), "第二次生成应标记已应用衰减");
    }

    @Test
    @DisplayName("可审计快照：calcInput 必须完整记录输入、权重与公式版本")
    void calcInputIsAuditable() {
        var input = new RadarCalculator.RadarInput(fullAssessment(), null, 50.0, 30.0, 80.0, 60.0, null, 0.35);
        var outcome = RadarCalculator.compute(input, "ASSESSMENT");

        assertNotNull(outcome.calcInput().get("assessment"), "快照必须包含测评输入");
        assertNotNull(outcome.calcInput().get("weights"), "快照必须包含权重表");
        assertEquals(RadarCalculator.FORMULA_VERSION, outcome.calcInput().get("formulaVersion"));
        assertNotNull(outcome.calcInput().get("alpha"));
    }

    @Test
    @DisplayName("学习力维度由任务完成率、连续打卡、按时完成三项构成")
    void learningAbilityUsesTaskMetrics() {
        var input = new RadarCalculator.RadarInput(null, null, 80.0, 100.0, 50.0, null, null, 0.35);
        var outcome = RadarCalculator.compute(input, "TASK");
        // 0.5×80 + 0.3×100 + 0.2×50 = 40 + 30 + 10 = 80
        assertEquals(80.0, scoreOf(outcome, "LEARNING_ABILITY"), 0.01);
    }

    @Test
    @DisplayName("分值恒被约束在 0–100 且保留一位小数")
    void scoresAreClampedAndRounded() {
        Map<String, Double> extreme = new LinkedHashMap<>();
        RadarCalculator.DIMENSION_KEYS.forEach(k -> extreme.put(k, 100.0));
        var input = new RadarCalculator.RadarInput(extreme, null, 100.0, 100.0, 100.0, 100.0, null, 0.35);
        var outcome = RadarCalculator.compute(input, "ASSESSMENT");

        for (var dim : outcome.dimensions()) {
            assertTrue(dim.finalScore() >= 0 && dim.finalScore() <= 100,
                    dim.key() + " 分值越界：" + dim.finalScore());
            assertEquals(Math.round(dim.finalScore() * 10) / 10.0, dim.finalScore(), 0.0001,
                    dim.key() + " 未保留一位小数");
        }
    }

    @Test
    @DisplayName("来源明细可追溯：每个被采用的来源都要记录分值来源与权重")
    void contributionsAreTraceable() {
        Map<String, Double> simulation = new LinkedHashMap<>();
        simulation.put("PROBLEM_SOLVING", 90.0);
        var outcome = RadarCalculator.compute(new RadarCalculator.RadarInput(
                fullAssessment(), simulation, null, null, null, null, null, 0.35), "SIMULATION");

        var proSkill = outcome.dimensions().stream()
                .filter(d -> d.key().equals("PRO_SKILL")).findFirst().orElseThrow();
        assertFalse(proSkill.contributions().isEmpty(), "专业技能必须记录来源明细");
        assertTrue(proSkill.contributions().stream().anyMatch(c -> c.source().equals("测评")));
        assertTrue(proSkill.contributions().stream().anyMatch(c -> c.source().equals("训练评分")));
    }

    private static double scoreOf(RadarCalculator.RadarOutcome outcome, String key) {
        return outcome.dimensions().stream().filter(d -> d.key().equals(key))
                .map(RadarCalculator.DimensionOutcome::finalScore).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("维度定义完整：8 个维度各有中文名，供界面与文档使用")
    void dimensionMetadataComplete() {
        List<String> keys = RadarCalculator.DIMENSION_KEYS;
        assertEquals(8, keys.size());
        for (String key : keys) {
            assertNotNull(RadarCalculator.DIMENSION_LABELS.get(key), key + " 缺少中文名");
        }
    }
}
