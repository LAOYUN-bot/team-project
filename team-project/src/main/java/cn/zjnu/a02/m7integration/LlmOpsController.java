package cn.zjnu.a02.m7integration;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.ai.AiService;
import cn.zjnu.a02.ai.cost.LlmCallLogService;
import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.platform.ratelimit.LlmRateLimiter;
import cn.zjnu.a02.platform.security.RequireRole;

/**
 * M7 集成与合规层 —— 模型调用统计与限流状态（NFR-8，v2.0 §3.5 / §3.4.4）。
 *
 * <p>两条路径：
 * <ul>
 *   <li>{@code GET /api/admin/llm-stats}：v2.0 规定的管理端接口，**仅 ADMIN**；</li>
 *   <li>{@code GET /api/platform/llm-stats}：同一份聚合数据的只读视图，
 *       供普通用户界面展示「本系统的 AI 花销与缓存命中率」——这是透明度要求的一部分
 *       （成本与降级状态对用户可见，才能建立信任）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class LlmOpsController {

    private final LlmCallLogService callLogService;
    private final LlmRateLimiter rateLimiter;
    private final AiService aiService;

    public LlmOpsController(LlmCallLogService callLogService, LlmRateLimiter rateLimiter,
                            AiService aiService) {
        this.callLogService = callLogService;
        this.rateLimiter = rateLimiter;
        this.aiService = aiService;
    }

    /** 管理端模型调用统计（仅管理员）。 */
    @GetMapping("/admin/llm-stats")
    @RequireRole({"ADMIN"})
    public ApiResponse<Map<String, Object>> adminStats(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> stats = callLogService.stats(days);
        return ApiResponse.ok(withLimitState(stats));
    }

    /** 面向所有登录用户的只读统计视图（界面「模型与成本」面板使用）。 */
    @GetMapping("/platform/llm-stats")
    public ApiResponse<Map<String, Object>> userStats(@RequestParam(defaultValue = "7") int days) {
        Map<String, Object> stats = callLogService.stats(days);
        return ApiResponse.ok(withLimitState(stats));
    }

    /** 限流状态（令牌桶剩余、今日调用量、告警阈值）。 */
    @GetMapping("/platform/llm-limit")
    public ApiResponse<Map<String, Object>> limitStatus() {
        return ApiResponse.ok(Map.of(
                "limit", rateLimiter.status(),
                "gateway", aiService.gatewayInfo()));
    }

    private Map<String, Object> withLimitState(Map<String, Object> stats) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(stats);
        result.put("limitState", rateLimiter.status());
        result.put("gateway", aiService.gatewayInfo());
        return result;
    }
}
