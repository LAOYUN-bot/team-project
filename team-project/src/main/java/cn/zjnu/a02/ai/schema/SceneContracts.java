package cn.zjnu.a02.ai.schema;

import java.util.List;
import java.util.Map;

/**
 * 各场景的结构化输出契约（架构文档 §7.4 步骤 [2]）。
 *
 * <p>把「必填键 / 非空数组 / 数值区间」集中声明，业务侧只需给出场景名，
 * 避免每个调用点重复书写校验规则。
 */
public final class SceneContracts {

    /** 场景 → 必填键。 */
    private static final Map<String, List<String>> REQUIRED = Map.of(
            "path", List.of("horizonYears", "stages"),
            "unit", List.of("units"),
            "trend", List.of("topic", "summary", "sources"),
            "score", List.of("overallScore", "level", "dimensions", "suggestions"),
            "incentive", List.of("message", "nextAction"),
            "resume", List.of("tags"));

    /** 场景 → 必须非空的数组键。 */
    private static final Map<String, List<String>> NON_EMPTY = Map.of(
            "path", List.of("stages"),
            "unit", List.of("units"),
            "trend", List.of("sources"),
            "score", List.of("dimensions", "suggestions"),
            "resume", List.of("tags"));

    /** 场景 → 数值区间。 */
    private static final Map<String, Map<String, double[]>> RANGES = Map.of(
            "path", Map.of("horizonYears", new double[]{3, 5}),
            "score", Map.of("overallScore", new double[]{0, 100}));

    private SceneContracts() {
    }

    public static List<String> requiredKeys(String scene) {
        return REQUIRED.getOrDefault(scene, List.of());
    }

    public static List<String> nonEmptyListKeys(String scene) {
        return NON_EMPTY.getOrDefault(scene, List.of());
    }

    public static Map<String, double[]> ranges(String scene) {
        return RANGES.getOrDefault(scene, Map.of());
    }
}
