package cn.zjnu.a02.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 提示词仓库（架构文档 §7.3）。
 *
 * <p>提示词存放于 {@code resources/prompts/*.txt} 并纳入 Git 版本管理；
 * 文件头部的 {@code # prompt: xxx | version: yyy} 注释即版本号，
 * 随生成结果落 {@code ai_generation_log.prompt_version}，做到可回滚、可对比。
 */
@Component
public class PromptLoader {

    private static final Pattern VERSION = Pattern.compile("version\\s*:\\s*([A-Za-z0-9_]+)");
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String get(String name) {
        return cache.computeIfAbsent(name, key -> {
            String path = "prompts/" + key + ".txt";
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("提示词缺失：" + path, e);
            }
        });
    }

    /** 读取提示词版本号；解析不到时返回 {@code name_v0}。 */
    public String version(String name) {
        Matcher m = VERSION.matcher(get(name));
        return m.find() ? m.group(1) : name + "_v0";
    }

    /** 简单占位符替换：{@code {role_card}} 等。 */
    public String render(String name, Map<String, String> vars) {
        String text = get(name);
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                text = text.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return text;
    }
}
