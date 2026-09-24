package cn.zjnu.a02.ai.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.zjnu.a02.ai.prompt.PromptLoader;
import cn.zjnu.a02.ai.schema.JsonExtractor;
import cn.zjnu.a02.ai.schema.SceneContracts;
import cn.zjnu.a02.ai.schema.SchemaValidator;
import cn.zjnu.a02.common.port.AiChatPort;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * 结构化输出契约模板方法（架构文档 §7.4，AD-3 —— 全项目最易翻车处）。
 *
 * <p>固定流水线：
 * <pre>
 * 模型原始输出
 *   → [1] 提取 JSON 块（容忍代码块围栏与前后说明文字）
 *   → [2] 本地修复（去尾逗号、去注释）
 *   → [3] Schema 校验（必填/非空/区间）
 *   → [4] 业务规则二次校验（阶段数≥4、每单元有产出物…）
 *   → [5] 失败则带「校验错误原因」回灌重试，最多 maxRetry 次
 *   → [6] 仍失败则使用兜底模板并标记 degraded
 * </pre>
 *
 * <p>子类只需实现 {@link #rawStructured}（真正的模型调用）与 {@link #chat} 等文本能力。
 */
public abstract class AbstractStructuredAiAdapter implements AiChatPort {

    protected final PromptLoader prompts;
    protected final JsonUtils json;
    private final int maxRetry;

    protected AbstractStructuredAiAdapter(PromptLoader prompts, JsonUtils json, int maxRetry) {
        this.prompts = prompts;
        this.json = json;
        this.maxRetry = Math.max(0, maxRetry);
    }

    /** 调用模型并返回原始文本；{@code repairHint} 为上一轮的校验错误（回灌给模型）。 */
    protected abstract String rawStructured(StructuredRequest request, String repairHint);

    /** 场景 → 提示词文件名。 */
    protected String promptName(String scene) {
        return switch (scene) {
            case "path", "unit", "trend", "score", "incentive", "resume" -> scene;
            default -> "persona";
        };
    }

    @Override
    public final StructuredResult structured(StructuredRequest request) {
        long start = System.currentTimeMillis();
        String scene = request.scene();
        String lastError = null;
        String raw = null;
        Map<String, Object> data = null;
        int retries = 0;
        List<String> trace = new ArrayList<>();

        while (true) {
            raw = rawStructured(request, lastError);
            String error = null;
            try {
                String candidate = JsonExtractor.repair(JsonExtractor.extract(raw));
                data = json.parseMap(candidate);
                error = SchemaValidator.validate(data,
                        request.requiredKeys() == null || request.requiredKeys().isEmpty()
                                ? SceneContracts.requiredKeys(scene) : request.requiredKeys(),
                        SceneContracts.nonEmptyListKeys(scene),
                        SceneContracts.ranges(scene));
                if (error == null) {
                    error = SchemaValidator.validateBusinessRules(data, scene);
                }
            } catch (Exception e) {
                error = "JSON 解析失败: " + e.getMessage();
                data = null;
            }

            if (error == null) {
                break;
            }
            trace.add("第 " + (retries + 1) + " 次输出未通过校验：" + error);
            if (retries >= maxRetry) {
                break;
            }
            lastError = error;
            retries++;
        }

        boolean fallbackUsed = false;
        if (data == null || SchemaValidator.validate(data,
                SceneContracts.requiredKeys(scene),
                SceneContracts.nonEmptyListKeys(scene),
                SceneContracts.ranges(scene)) != null) {
            data = json.parseMap(JsonExtractor.extract(request.fallback()));
            fallbackUsed = true;
        }

        Map<String, Object> ctx = request.context();
        boolean markedDegraded = ctx != null && Boolean.TRUE.equals(ctx.get("__degraded"));

        return new StructuredResult(
                raw,
                data,
                provider(),
                modelName(),
                prompts.version(promptName(scene)),
                retries,
                fallbackUsed || markedDegraded,
                fallbackUsed,
                System.currentTimeMillis() - start,
                fallbackUsed ? String.join(" | ", trace) : lastError);
    }

    /** 模型标识，便于 ai_generation_log 追溯。 */
    protected abstract String modelName();

    /** 便捷：把给定结构序列化为 JSON 文本（子类构造模拟输出时使用）。 */
    protected String toJson(Object value) {
        return json.stringify(value);
    }

    /** 便捷：读取上下文中的列表字段。 */
    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> ctxList(Map<String, Object> ctx, String key) {
        Object value = ctx.get(key);
        if (value instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    protected String ctxString(Map<String, Object> ctx, String key, String defaultValue) {
        Object value = ctx.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    protected int ctxInt(Map<String, Object> ctx, String key, int defaultValue) {
        Object value = ctx.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 去重并保持顺序。 */
    protected static List<String> distinct(List<String> values) {
        Set<String> set = new LinkedHashSet<>(values);
        return new ArrayList<>(set);
    }

    protected static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
