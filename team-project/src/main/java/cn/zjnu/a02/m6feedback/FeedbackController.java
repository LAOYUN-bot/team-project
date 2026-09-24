package cn.zjnu.a02.m6feedback;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * M6 反馈层接口：成长面板、两时点对比、偏离提示、陪伴激励。
 *
 * <p>对应需求：FR-6.1 / FR-6.2 / FR-6.3 / FR-6.4。
 */
@RestController
@RequestMapping("/api")
public class FeedbackController {

    private final GrowthService growth;
    private final CompanionService companion;

    public FeedbackController(GrowthService growth, CompanionService companion) {
        this.growth = growth;
        this.companion = companion;
    }

    /** 成长面板：一次请求返回雷达 / 进度环 / 成长曲线 / 热力图 4 类数据。 */
    @GetMapping("/growth/panel")
    public ApiResponse<Map<String, Object>> panel() {
        return ApiResponse.ok(growth.panel(AuthContext.userId()));
    }

    @GetMapping("/growth/compare")
    public ApiResponse<Map<String, Object>> compare(@RequestParam("a") int a, @RequestParam("b") int b) {
        return ApiResponse.ok(growth.compare(AuthContext.userId(), a, b));
    }

    @GetMapping("/growth/snapshots")
    public ApiResponse<List<Map<String, Object>>> snapshots() {
        return ApiResponse.ok(growth.snapshots(AuthContext.userId()));
    }

    @PostMapping("/growth/alerts/{alertId}/resolve")
    public ApiResponse<Map<String, Object>> resolveAlert(@PathVariable long alertId) {
        return ApiResponse.ok(growth.resolveAlert(AuthContext.userId(), alertId));
    }

    @GetMapping("/growth/formula")
    public ApiResponse<Map<String, Object>> formula() {
        return ApiResponse.ok(growth.formula());
    }

    /** 陪伴状态：打卡、里程碑、下一个动作、激励文案（FR-6.4）。 */
    @GetMapping("/companion/state")
    public ApiResponse<Map<String, Object>> state() {
        return ApiResponse.ok(companion.state(AuthContext.userId()));
    }

    @GetMapping("/companion/incentives")
    public ApiResponse<List<Map<String, Object>>> incentives() {
        return ApiResponse.ok(companion.incentives(AuthContext.userId()));
    }

    /** 情绪感知（FR-6.4）：规则判定情绪标签，回复由 AI 伙伴生成。 */
    @PostMapping("/companion/emotion")
    public ApiResponse<Map<String, Object>> emotion(@RequestBody EmotionRequest request) {
        return ApiResponse.ok(companion.emotionProbe(request.text()));
    }

    public record EmotionRequest(String text) {
    }
}
