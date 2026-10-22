package cn.zjnu.a02.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;
import cn.zjnu.a02.common.util.JsonUtils;

/**
 * MCP 协议端点（FR-5.5，企业专项材料③）。
 *
 * <p>两条通道：
 * <ol>
 *   <li>{@code POST /mcp}：标准 JSON-RPC 2.0（{@code initialize} / {@code tools/list} / {@code tools/call}），
 *       供外部 MCP 客户端（智能体/IDE 插件）通过 HTTP 传输调用；</li>
 *   <li>{@code GET /internal/v1/**}：**只读内部 API**，用独立 Service Token 保护，
 *       供自建的 Python MCP Server 取数（避免两套数据访问逻辑，AD-8）。</li>
 * </ol>
 * 演示界面通过 {@code /api/mcp/**} 调用，可见调用日志。
 */
@RestController
public class McpController {

    private final McpToolService tools;
    private final String serviceToken;
    private final JsonUtils json;

    public McpController(McpToolService tools,
                         @Value("${a02.internal.service-token:a02-internal-demo-token}") String serviceToken,
                         JsonUtils json) {
        this.tools = tools;
        this.serviceToken = serviceToken;
        this.json = json;
    }

    // ==================================================================
    // ① JSON-RPC 2.0 端点（MCP 传输层）
    // ==================================================================

    @PostMapping("/mcp")
    public Map<String, Object> jsonRpc(@RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        String method = String.valueOf(request.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        try {
            Map<String, Object> result = switch (method) {
                case "initialize" -> Map.of(
                        "protocolVersion", "2024-11-05",
                        "serverInfo", Map.of("name", "a02-career-mcp", "version", "1.0.0"),
                        "capabilities", Map.of("tools", Map.of("listChanged", false)));
                case "tools/list" -> Map.of("tools", tools.toolCatalog().stream()
                        .map(t -> Map.of("name", t.get("name"),
                                "description", t.get("description"),
                                "inputSchema", t.get("inputSchema")))
                        .toList());
                case "tools/call" -> {
                    String name = String.valueOf(params.get("name"));
                    @SuppressWarnings("unchecked")
                    Map<String, Object> args = params.get("arguments") instanceof Map<?, ?> a
                            ? (Map<String, Object>) a : Map.of();
                    Map<String, Object> call = tools.call(name, args);
                    // MCP 规范要求 content[].text 为字符串，这里输出标准 JSON（而非 Java Map.toString）
                    yield Map.of("content", List.of(Map.of(
                                    "type", "text",
                                    "text", json.stringify(call.get("result")))),
                            "isError", !"OK".equals(call.get("status")));
                }
                default -> throw new McpToolService.McpException("METHOD_NOT_FOUND",
                        "不支持的方法：" + method);
            };
            return Map.of("jsonrpc", "2.0", "id", id == null ? 1 : id, "result", result);
        } catch (McpToolService.McpException e) {
            return Map.of("jsonrpc", "2.0", "id", id == null ? 1 : id,
                    "error", Map.of("code", -32601, "message", e.getMessage()));
        }
    }

    // ==================================================================
    // ② 演示界面用的工具调用与日志
    // ==================================================================

    @GetMapping("/api/mcp/tools")
    public ApiResponse<Map<String, Object>> catalog() {
        return ApiResponse.ok(Map.of(
                "server", Map.of(
                        "name", "a02-career-mcp",
                        "transport", "stdio（本地演示） + HTTP /mcp（远程）",
                        "protocolVersion", "2024-11-05",
                        "readOnly", true),
                "tools", tools.toolCatalog(),
                "note", "所有工具均为只读；入参做长度与字符白名单校验，防止提示注入经工具外溢。"));
    }

    @PostMapping("/api/mcp/call")
    public ApiResponse<Map<String, Object>> call(@RequestBody CallRequest request) {
        return ApiResponse.ok(tools.call(request.tool(), request.arguments()));
    }

    @GetMapping("/api/mcp/logs")
    public ApiResponse<List<Map<String, Object>>> logs(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(tools.logs(limit));
    }

    @GetMapping("/api/mcp/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(tools.logStats());
    }

    // ==================================================================
    // ③ 只读内部 API（供自建 MCP Server 取数，独立 Service Token）
    // ==================================================================

    @GetMapping("/internal/v1/jobs")
    public Map<String, Object> internalJobs(@RequestHeader(value = "X-Service-Token", required = false) String token,
                                            @RequestParam String keyword,
                                            @RequestParam(required = false) String region) {
        verifyServiceToken(token);
        return tools.call("query_job_market", Map.of("keyword", keyword,
                "region", region == null ? "" : region));
    }

    @GetMapping("/internal/v1/courses")
    public Map<String, Object> internalCourses(@RequestHeader(value = "X-Service-Token", required = false) String token,
                                               @RequestParam String skill,
                                               @RequestParam(defaultValue = "5") int limit) {
        verifyServiceToken(token);
        return tools.call("search_course", Map.of("skill", skill, "limit", limit));
    }

    @GetMapping("/internal/v1/certs")
    public Map<String, Object> internalCerts(@RequestHeader(value = "X-Service-Token", required = false) String token,
                                             @RequestParam("cert_name") String certName) {
        verifyServiceToken(token);
        return tools.call("verify_certificate", Map.of("cert_name", certName));
    }

    private void verifyServiceToken(String token) {
        if (token == null || !serviceToken.equals(token)) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "内部接口需携带有效的 X-Service-Token（独立于用户 JWT，权限最小化）");
        }
    }

    public record CallRequest(String tool, Map<String, Object> arguments) {
    }
}
