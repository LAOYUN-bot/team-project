package cn.zjnu.a02.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.zjnu.a02.common.db.Db;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * MCP 工具服务（FR-5.5，企业专项材料③）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>全部只读</b>：不暴露任何写操作，不返回个人隐私字段；</li>
 *   <li><b>入参白名单</b>：长度与字符集校验，防止提示注入经工具外溢（D-5）；</li>
 *   <li><b>三类异常显式处理</b>：超时 / 非法入参 / 上游不可用，一律返回结构化错误，绝不抛栈给模型；</li>
 *   <li><b>全量落日志</b>：{@code mcp_tool_log} 记录工具名、入参摘要、耗时、状态。</li>
 * </ul>
 */
@Service
public class McpToolService {

    private static final Logger log = LoggerFactory.getLogger(McpToolService.class);

    /** 允许的入参字符集与长度（中文、字母、数字、空格与常见技术符号）。 */
    private static final Pattern SAFE_TEXT = Pattern.compile("^[\\u4e00-\\u9fa5A-Za-z0-9 _\\-+.+#/]{1,50}$");
    private static final long TIMEOUT_MS = 5000L;

    private final Db db;
    private final JsonUtils json;

    public McpToolService(Db db, JsonUtils json) {
        this.db = db;
        this.json = json;
    }

    /** 工具清单（tools/list 的返回体，也是界面展示的契约表）。 */
    public List<Map<String, Object>> toolCatalog() {
        return List.of(
                Map.of(
                        "name", "query_job_market",
                        "title", "岗位市场需求查询",
                        "description", "按岗位名或技能关键词查询岗位需求与所需技能（只读，不返回个人信息）",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "keyword", Map.of("type", "string", "minLength", 1, "maxLength", 50,
                                                "description", "岗位名或技能关键词，如「Java 后端」"),
                                        "region", Map.of("type", "string", "minLength", 1, "maxLength", 20,
                                                "description", "地区（可选），当前 Demo 不区分地区")),
                                "required", List.of("keyword"))),
                Map.of(
                        "name", "search_course",
                        "title", "学习资源检索",
                        "description", "按技能检索课程资源，每条结果均带来源（只读）",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "skill", Map.of("type", "string", "minLength", 1, "maxLength", 50),
                                        "level", Map.of("type", "string",
                                                "enum", List.of("BEGINNER", "INTERMEDIATE", "ADVANCED")),
                                        "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20)),
                                "required", List.of("skill"))),
                Map.of(
                        "name", "verify_certificate",
                        "title", "职业认证信息查询",
                        "description", "查询认证的颁发机构与有效期，绝不返回任何个人证书编号（只读）",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "cert_name", Map.of("type", "string", "minLength", 1, "maxLength", 50)),
                                "required", List.of("cert_name"))));
    }

    /**
     * 统一调用入口：负责鉴权后的参数校验、耗时统计与日志落库。
     *
     * @return 结构化结果；失败时返回 {@code {error, message}} 而**不抛异常**
     */
    public Map<String, Object> call(String toolName, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        String status = "OK";
        String summary = "";
        Map<String, Object> result;
        try {
            result = switch (toolName) {
                case "query_job_market" -> queryJobMarket(str(args, "keyword"), str(args, "region"));
                case "search_course" -> searchCourse(str(args, "skill"), str(args, "level"),
                        intArg(args, "limit", 5));
                case "verify_certificate" -> verifyCertificate(str(args, "cert_name"));
                default -> Map.of("error", "INVALID_ARGUMENT",
                        "message", "未知工具：" + toolName + "。可用工具见 tools/list。");
            };
            if (result.containsKey("error")) {
                status = String.valueOf(result.get("error"));
            }
            summary = abbreviate(json.stringify(result), 280);
        } catch (McpException e) {
            status = e.code;
            result = Map.of("error", e.code, "message", e.getMessage());
            summary = e.getMessage();
            log.warn("MCP 工具调用失败 tool={} code={} msg={}", toolName, e.code, e.getMessage());
        } catch (Exception e) {
            status = "UPSTREAM_UNAVAILABLE";
            result = Map.of("error", "UPSTREAM_UNAVAILABLE",
                    "message", "内部数据源暂不可用，请稍后重试");
            summary = e.getMessage() == null ? "unknown" : abbreviate(e.getMessage(), 200);
            log.error("MCP 工具异常 tool={}", toolName, e);
        }
        long elapsed = System.currentTimeMillis() - start;

        db.insert("INSERT INTO mcp_tool_log(tool_name, args_digest, status, elapsed_ms, result_summary)"
                        + " VALUES (?,?,?,?,?)",
                toolName, abbreviate(json.stringify(args), 280), status, elapsed, summary);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tool", toolName);
        response.put("status", status);
        response.put("elapsedMs", elapsed);
        response.put("result", result);
        response.put("logged", true);
        return response;
    }

    // ------------------------------------------------------------------
    // 三个只读工具
    // ------------------------------------------------------------------

    private Map<String, Object> queryJobMarket(String keyword, String region) {
        requireSafe(keyword, "keyword", 1, 50);
        if (region != null && !region.isBlank()) {
            requireSafe(region, "region", 1, 20);
        }
        List<Map<String, Object>> rows = db.camelList(
                "SELECT id, code, name, attrs_json FROM kg_entity WHERE type='JOB'"
                        + " AND (name LIKE ? OR code LIKE ?)", "%" + keyword + "%", "%" + keyword + "%");
        List<Map<String, Object>> jobs = new ArrayList<>();
        for (Map<String, Object> job : rows) {
            long jobId = ((Number) job.get("id")).longValue();
            List<Map<String, Object>> skills = db.camelList(
                    "SELECT s.name AS skill_name, r.weight FROM kg_relation r"
                            + " JOIN kg_entity s ON s.id = r.object_id"
                            + " WHERE r.subject_id=? AND r.predicate='REQUIRES'"
                            + " ORDER BY r.weight DESC LIMIT 6", jobId);
            List<String> topSkills = new ArrayList<>();
            double weightSum = 0;
            for (Map<String, Object> skill : skills) {
                topSkills.add(String.valueOf(skill.get("skillName")));
                weightSum += ((Number) skill.get("weight")).doubleValue();
            }
            long totalSkills = db.count("SELECT COUNT(*) FROM kg_relation WHERE subject_id=? AND predicate='REQUIRES'",
                    jobId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("jobId", jobId);
            item.put("name", job.get("name"));
            item.put("code", job.get("code"));
            // 热度分：由「要求技能数 × 平均权重」归一化得出，口径可解释（非模型编造）
            item.put("hotScore", Math.round(Math.min(100, totalSkills * 2.2 + weightSum * 1.2) * 10) / 10.0);
            item.put("skillCount", totalSkills);
            item.put("topSkills", topSkills);
            jobs.add(item);
        }
        if (jobs.isEmpty()) {
            return Map.of("jobs", List.of(),
                    "message", "未找到与「" + keyword + "」匹配的岗位。可尝试更通用的关键词，例如「Java」「数据」「前端」「AI」。",
                    "region", region == null ? "全国（Demo 不区分地区）" : region);
        }
        return Map.of("jobs", jobs, "count", jobs.size(),
                "region", region == null ? "全国（Demo 不区分地区）" : region,
                "source", "本系统职业知识图谱（公开 JD 脱敏聚合 + 自构造模拟数据）");
    }

    private Map<String, Object> searchCourse(String skill, String level, int limit) {
        requireSafe(skill, "skill", 1, 50);
        if (level != null && !level.isBlank()
                && !List.of("BEGINNER", "INTERMEDIATE", "ADVANCED").contains(level.toUpperCase())) {
            throw new McpException("INVALID_ARGUMENT", "level 取值须为 BEGINNER/INTERMEDIATE/ADVANCED");
        }
        int size = Math.max(1, Math.min(20, limit));
        List<Map<String, Object>> rows = db.camelList(
                "SELECT DISTINCT c.name AS title, c.attrs_json FROM kg_entity c"
                        + " JOIN kg_relation r ON r.subject_id = c.id AND r.predicate='TEACHES'"
                        + " JOIN kg_entity s ON s.id = r.object_id"
                        + " WHERE c.type='COURSE' AND (s.name LIKE ? OR s.code LIKE ?) LIMIT ?",
                "%" + skill + "%", "%" + skill + "%", size);
        if (rows.isEmpty()) {
            rows = db.camelList("SELECT name AS title, attrs_json FROM kg_entity"
                    + " WHERE type='COURSE' AND name LIKE ? LIMIT ?", "%" + skill + "%", size);
        }
        List<Map<String, Object>> courses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", row.get("title"));
            item.put("level", level == null ? "BEGINNER" : level.toUpperCase());
            item.put("source", "知识图谱课程库 · " + String.valueOf(row.getOrDefault("attrsJson", "公开课程")));
            courses.add(item);
        }
        if (courses.isEmpty()) {
            return Map.of("courses", List.of(),
                    "message", "知识库中暂无与「" + skill + "」匹配的课程资源，请更换关键词或等待知识库更新。");
        }
        return Map.of("courses", courses, "count", courses.size(),
                "note", "每条结果均带来源；无来源的内容不会返回（防幻觉要求）");
    }

    private Map<String, Object> verifyCertificate(String certName) {
        requireSafe(certName, "cert_name", 1, 50);
        List<Map<String, Object>> rows = db.camelList("SELECT name, attrs_json FROM kg_entity"
                + " WHERE type='CERT' AND name LIKE ? LIMIT 3", "%" + certName + "%");
        if (rows.isEmpty()) {
            return Map.of("cert", null,
                    "message", "知识库中暂无「" + certName + "」相关认证信息，请核实名称或咨询发证机构官方渠道。");
        }
        List<Map<String, Object>> certs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", row.get("name"));
            item.put("detail", row.get("attrsJson"));
            item.put("personalDataIncluded", false);
            certs.add(item);
        }
        return Map.of("cert", certs.get(0), "candidates", certs,
                "privacyNote", "本工具绝不返回任何个人证书编号或持证人信息");
    }

    // ------------------------------------------------------------------
    // 校验与工具方法
    // ------------------------------------------------------------------

    private void requireSafe(String value, String field, int min, int max) {
        if (value == null || value.isBlank()) {
            throw new McpException("INVALID_ARGUMENT", "缺少必填参数：" + field);
        }
        String trimmed = value.trim();
        if (trimmed.length() < min || trimmed.length() > max) {
            throw new McpException("INVALID_ARGUMENT",
                    "参数 " + field + " 长度须在 " + min + "-" + max + " 之间");
        }
        if (!SAFE_TEXT.matcher(trimmed).matches()) {
            throw new McpException("INVALID_ARGUMENT",
                    "参数 " + field + " 含非法字符（仅允许中文、字母、数字与常用符号）");
        }
    }

    private static String str(Map<String, Object> args, String key) {
        if (args == null) {
            return null;
        }
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intArg(Map<String, Object> args, String key, int defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
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

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    public List<Map<String, Object>> logs(int limit) {
        return db.camelList("SELECT id, tool_name, args_digest, status, elapsed_ms, result_summary, created_at"
                + " FROM mcp_tool_log ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(100, limit)));
    }

    public Map<String, Object> logStats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", db.count("SELECT COUNT(*) FROM mcp_tool_log"));
        result.put("byStatus", db.camelList("SELECT status, COUNT(*) AS cnt FROM mcp_tool_log"
                + " GROUP BY status ORDER BY cnt DESC"));
        result.put("byTool", db.camelList("SELECT tool_name, COUNT(*) AS cnt FROM mcp_tool_log"
                + " GROUP BY tool_name ORDER BY cnt DESC"));
        result.put("avgElapsedMs", db.one("SELECT ROUND(AVG(elapsed_ms),1) AS avg FROM mcp_tool_log"));
        return result;
    }

    /** 工具层内部异常：带 MCP 错误码，绝不外泄堆栈。 */
    public static class McpException extends RuntimeException {
        private final String code;

        public McpException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
