package cn.zjnu.a02.ai.schema;

import java.util.List;
import java.util.Map;

/**
 * 结构化输出契约的轻量校验器（架构文档 §7.4 步骤 [2]）。
 *
 * <p>校验内容：必填键存在且非空；数组型字段不得为空数组；数值型字段落在给定区间。
 * 生产环境可替换为完整 JSON Schema 校验器，接口保持不变。
 */
public final class SchemaValidator {

    private SchemaValidator() {
    }

    /**
     * @param data         已解析的 JSON 对象
     * @param requiredKeys 必填键
     * @param nonEmptyListKeys 必须非空的数组键
     * @param ranges       数值区间约束：key → [min, max]
     * @return 校验错误说明；通过时返回 {@code null}
     */
    public static String validate(Map<String, Object> data,
                                  List<String> requiredKeys,
                                  List<String> nonEmptyListKeys,
                                  Map<String, double[]> ranges) {
        if (data == null || data.isEmpty()) {
            return "输出为空对象";
        }
        if (requiredKeys != null) {
            for (String key : requiredKeys) {
                Object value = data.get(key);
                if (value == null) {
                    return "缺少必填字段: " + key;
                }
                if (value instanceof String s && s.isBlank()) {
                    return "必填字段为空: " + key;
                }
                if (value instanceof List<?> l && l.isEmpty()) {
                    return "必填数组字段为空: " + key;
                }
            }
        }
        if (nonEmptyListKeys != null) {
            for (String key : nonEmptyListKeys) {
                Object value = data.get(key);
                if (!(value instanceof List<?> list) || list.isEmpty()) {
                    return "字段必须为非空数组: " + key;
                }
            }
        }
        if (ranges != null) {
            for (Map.Entry<String, double[]> e : ranges.entrySet()) {
                Object value = data.get(e.getKey());
                if (value instanceof Number n) {
                    double v = n.doubleValue();
                    if (v < e.getValue()[0] || v > e.getValue()[1]) {
                        return "字段 " + e.getKey() + " 超出允许区间 [" + e.getValue()[0] + "," + e.getValue()[1] + "]: " + v;
                    }
                }
            }
        }
        return null;
    }

    /** 业务规则二次校验（架构文档 §7.4 步骤 [5]）：例如「阶段数 ≥4」「每阶段有产出物」。 */
    public static String validateBusinessRules(Map<String, Object> data, String scene) {
        if ("path".equals(scene)) {
            Object stages = data.get("stages");
            if (!(stages instanceof List<?> list)) {
                return "path 场景缺少 stages 数组";
            }
            if (list.size() < 4) {
                return "职业路径阶段数必须 ≥4，实际 " + list.size();
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> stage) {
                    Object outputs = stage.get("outputs");
                    if (!(outputs instanceof List<?> o) || o.isEmpty()) {
                        return "阶段「" + stage.get("name") + "」缺少可验证产出物(outputs)";
                    }
                }
            }
        }
        if ("unit".equals(scene)) {
            Object units = data.get("units");
            if (units instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> unit) {
                        Object hours = unit.get("estHours");
                        if (hours == null) {
                            return "学习单元缺少预计工时(estHours)";
                        }
                    }
                }
            }
        }
        if ("score".equals(scene)) {
            Object dims = data.get("dimensions");
            if (!(dims instanceof List<?> list) || list.isEmpty()) {
                return "评分结果缺少 dimensions";
            }
        }
        return null;
    }
}
