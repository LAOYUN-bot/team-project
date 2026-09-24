package cn.zjnu.a02.ai.schema;

/**
 * JSON 抽取器（架构文档 §7.4 步骤 [1]）。
 *
 * <p>真实大模型经常把 JSON 包在 Markdown 代码块里，或在前后附带解释性文字；
 * 直接 {@code parse} 会失败。本类负责把这些「脏」输出还原为纯 JSON 文本。
 */
public final class JsonExtractor {

    private JsonExtractor() {
    }

    public static String extract(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();

        // 1) 优先取 ```json ... ``` 或 ``` ... ``` 代码块
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            if (contentStart > 0) {
                int fenceEnd = text.indexOf("```", contentStart);
                if (fenceEnd > contentStart) {
                    text = text.substring(contentStart + 1, fenceEnd).trim();
                }
            }
        }

        // 2) 截取第一个 '{' 到最后一个 '}'（容忍前后说明文字）
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * 修复常见缺陷（架构文档 §7.4 步骤 [3] 的本地修复能力）：
     * 去掉对象/数组尾部的多余逗号。
     */
    public static String repair(String json) {
        if (json == null) {
            return null;
        }
        String repaired = json.replaceAll(",\\s*([}\\]])", "$1");
        // 去掉行内注释（部分模型会输出 // 注释）
        repaired = repaired.replaceAll("(?m)^\\s*//.*$", "");
        return repaired.trim();
    }
}
