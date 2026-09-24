package cn.zjnu.a02.ai.privacy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.util.MaskingUtils;

/**
 * 传模型前脱敏管道（架构文档 §9.2 / NFR-3：敏感信息不上传公网模型）。
 *
 * <p>规则：
 * <ol>
 *   <li>结构化字段：{@code name/realName} → 用户A、{@code phone} → 掩码、{@code email} → 掩码；</li>
 *   <li>自由文本：手机号、邮箱、身份证号正则替换；</li>
 *   <li>院校与单位泛化为层级（如「某某大学」→「本科院校」），降低偏见触发面。</li>
 * </ol>
 */
@Component
public class PiiRedactor {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("\\d{17}[\\dXx]");
    private static final Pattern SCHOOL = Pattern.compile("([\\u4e00-\\u9fa5]{2,12}(大学|学院|职业技术学院))");

    /** 结构化上下文脱敏（递归）。 */
    public Map<String, Object> redact(Map<String, Object> context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        context.forEach((k, v) -> out.put(k, redactValue(k, v)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object redactValue(String key, Object value) {
        String lower = key == null ? "" : key.toLowerCase();
        if (value instanceof String s) {
            if (lower.contains("name") && !lower.contains("jobname") && !lower.contains("skillname")
                    && !lower.contains("stagename") && !lower.contains("scenarioname")) {
                return MaskingUtils.maskName(s);
            }
            if (lower.contains("phone") || lower.contains("mobile")) {
                return MaskingUtils.maskPhone(s);
            }
            if (lower.contains("email") || lower.contains("mail")) {
                return MaskingUtils.maskEmail(s);
            }
            if (lower.contains("school") || lower.contains("university")) {
                return SCHOOL.matcher(s).replaceAll("本科院校");
            }
            return redactText(s);
        }
        if (value instanceof Map<?, ?> m) {
            return redact((Map<String, Object>) m);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> item instanceof String s ? redactText(s) : item).toList();
        }
        return value;
    }

    /** 自由文本脱敏。 */
    public String redactText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String out = ID_CARD.matcher(text).replaceAll("[身份证号已脱敏]");
        out = PHONE.matcher(out).replaceAll(m -> MaskingUtils.maskPhone(m.group()));
        out = EMAIL.matcher(out).replaceAll(m -> MaskingUtils.maskEmail(m.group()));
        out = SCHOOL.matcher(out).replaceAll("本科院校");
        return out;
    }
}
