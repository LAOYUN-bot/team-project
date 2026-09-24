package cn.zjnu.a02.common.db;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import cn.zjnu.a02.common.util.JsonUtils;

/**
 * 轻量数据访问封装（架构文档 §6.1：MySQL/H2 是唯一业务真相源）。
 *
 * <p>本 Demo 使用 Spring {@code JdbcTemplate} 而非 MyBatis-Plus：
 * 运行环境为离线仓库，缺少 MyBatis-Plus 依赖；DAO 边界已收敛到本类，
 * 迁移到 MyBatis-Plus 时业务代码无需改动（迁移说明见 README「技术选型偏差」）。
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public Db(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /** 查询列表，键为数据库列名（H2 已配置 DATABASE_TO_LOWER，统一小写下划线）。 */
    public List<Map<String, Object>> list(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    /** 查询单行，无结果返回 null。 */
    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = list(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询列表并映射为记录类型（snake_case → camelCase 自动归一）。 */
    public <T> List<T> listAs(Class<T> type, String sql, Object... args) {
        return list(sql, args).stream().map(row -> json.convert(row, type)).toList();
    }

    /** 查询单行并映射为记录类型。 */
    public <T> T oneAs(Class<T> type, String sql, Object... args) {
        Map<String, Object> row = one(sql, args);
        return row == null ? null : json.convert(row, type);
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    public Long scalarLong(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** 插入并返回自增主键。 */
    public long insert(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // 显式指定返回列名：H2 在 RETURN_GENERATED_KEYS 下会回传整行，
            // 只取 id 列才能让 KeyHolder 拿到单一主键（MySQL 同样兼容此写法）。
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 便捷：把一行 Map 转成 camelCase 的 Map，用于组装自定义响应结构。 */
    public Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (row == null) {
            return out;
        }
        row.forEach((k, v) -> out.put(JsonUtils.toCamel(k), v));
        return out;
    }

    public List<Map<String, Object>> camelList(String sql, Object... args) {
        return list(sql, args).stream().map(this::camel).toList();
    }
}
