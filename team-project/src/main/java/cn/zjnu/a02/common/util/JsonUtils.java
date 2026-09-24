package cn.zjnu.a02.common.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON 工具：全项目唯一直接依赖 Jackson 的位置。
 *
 * <p>隔离原因：Spring Boot 4 默认携带 Jackson 3（包名 {@code tools.jackson.*}），
 * 与 Jackson 2 的 {@code com.fasterxml.jackson.*} 不同；集中封装后升级/替换只改此处。
 */
@Component
public class JsonUtils {

    private final ObjectMapper mapper;

    public JsonUtils() {
        this.mapper = JsonMapper.builder().build();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String stringify(Object value) {
        if (value == null) {
            return null;
        }
        return mapper.writeValueAsString(value);
    }

    public <T> T parse(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return mapper.readValue(json, Map.class);
    }

    @SuppressWarnings("unchecked")
    public List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, List.class);
    }

    /** 把实体对象转换为 Map（键为实体属性名），便于接口层输出 camelCase 视图。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(Object entity) {
        if (entity == null) {
            return new LinkedHashMap<>();
        }
        return mapper.convertValue(entity, Map.class);
    }

    /** 把任意对象（如 Map 行数据）转换为目标类型，字段名做 snake_case → camelCase 归一化。 */
    public <T> T convert(Object source, Class<T> type) {
        return mapper.convertValue(normalize(source), type);
    }

    /** 递归把 Map 的 snake_case 键改写为 camelCase，便于直接映射到 Java 记录。 */
    public static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(toCamel(String.valueOf(k)), normalize(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(JsonUtils::normalize).toList();
        }
        return value;
    }

    public static String toCamel(String key) {
        if (key == null || key.indexOf('_') < 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key.length());
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
