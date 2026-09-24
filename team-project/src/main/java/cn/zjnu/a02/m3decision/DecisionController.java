package cn.zjnu.a02.m3decision;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.m1perception.PerceptionService;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * M3 决策层接口：能力画像（版本化）、职业路径（SSE 流式）、岗位匹配度。
 *
 * <p>对应需求：FR-3.1 / FR-3.2 / FR-3.3 / FR-3.4；NFR-1（流式首字延迟）。
 */
@RestController
@RequestMapping("/api")
public class DecisionController {

    private static final Logger log = LoggerFactory.getLogger(DecisionController.class);

    private final RadarService radarService;
    private final CareerPathService careerPathService;
    private final MatchService matchService;
    private final PerceptionService perception;
    private final JsonUtils json;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "a02-sse");
        t.setDaemon(true);
        return t;
    });

    public DecisionController(RadarService radarService, CareerPathService careerPathService,
                              MatchService matchService, PerceptionService perception, JsonUtils json) {
        this.radarService = radarService;
        this.careerPathService = careerPathService;
        this.matchService = matchService;
        this.perception = perception;
        this.json = json;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 能力画像（FR-3.1）
    // ------------------------------------------------------------------

    /** 获取雷达图数据，支持版本参数（FR-3.1「历史版本可对比」）。 */
    @GetMapping("/profile/radar")
    public ApiResponse<Map<String, Object>> radar(@RequestParam(required = false) Integer version) {
        long userId = AuthContext.userId();
        if (version == null || version <= 0) {
            return ApiResponse.ok(radarService.latest(userId));
        }
        Map<String, Object> row = radarService.versions(userId).stream()
                .filter(v -> String.valueOf(version).equals(String.valueOf(v.get("version"))))
                .findFirst()
                .orElse(null);
        if (row == null) {
            return ApiResponse.ok(radarService.latest(userId));
        }
        return ApiResponse.ok(radarService.describe(((Number) row.get("radarId")).longValue()));
    }

    @GetMapping("/profile/radar/versions")
    public ApiResponse<List<Map<String, Object>>> radarVersions() {
        return ApiResponse.ok(radarService.versions(AuthContext.userId()));
    }

    /** 两时点对比（FR-6.2）。 */
    @GetMapping("/profile/radar/compare")
    public ApiResponse<Map<String, Object>> compareRadar(@RequestParam("a") int a, @RequestParam("b") int b) {
        return ApiResponse.ok(radarService.compare(AuthContext.userId(), a, b));
    }

    /** 手动触发重算（MANUAL）：用于演示「画像动态迭代」。 */
    @PostMapping("/profile/radar/recalculate")
    public ApiResponse<Map<String, Object>> recalculate() {
        long userId = AuthContext.userId();
        Long jobId = perception.targetJobId(userId);
        return ApiResponse.ok(radarService.createVersion(userId, "MANUAL", jobId));
    }

    /** 画像公式说明（答辩用：分值怎么来的）。 */
    @GetMapping("/profile/radar/formula")
    public ApiResponse<Map<String, Object>> formula() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formulaVersion", RadarCalculator.FORMULA_VERSION);
        result.put("dimensions", RadarCalculator.DIMENSION_KEYS.stream()
                .map(key -> Map.of("key", key, "label", RadarCalculator.DIMENSION_LABELS.get(key)))
                .toList());
        result.put("weightTable", RadarCalculator.weightTable());
        result.put("formula", Map.of(
                "raw", "raw = Σ(来源分值 × 来源权重) / Σ(来源权重)   // 缺失来源自动重归一化",
                "decay", "final = old × (1 − α) + raw × α            // α 存于 sys_config，默认 0.35",
                "gap", "gap_i = max(0, 岗位要求_i − 能力_i)",
                "matchRate", "matchRate = 100 × Σ(要求_i × 能力_i/100) / Σ(要求_i)"));
        result.put("note", "每次计算的输入与权重都会写入 competency_radar.calc_input_json，历史版本可复算");
        return ApiResponse.ok(result);
    }

    // ------------------------------------------------------------------
    // 职业路径（FR-3.2 / FR-3.3）
    // ------------------------------------------------------------------

    /**
     * 生成 3–5 年路径（SSE 流式）。
     *
     * <p>采用 SSE 的原因（NFR-1）：长任务需要让用户看到「正在分析 → 正在生成 → 校验 → 完成」，
     * 而不是一个转圈等待。前端用 {@code fetch + ReadableStream} 解析（EventSource 无法带 Authorization 头）。
     */
    @PostMapping(value = "/career/path/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generatePath(@RequestBody(required = false) PathRequest request) {
        long userId = AuthContext.userId();
        Integer horizonYears = request == null ? null : request.horizonYears();
        boolean chaos = request != null && Boolean.TRUE.equals(request.chaos());
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.submit(() -> {
            long start = System.currentTimeMillis();
            try {
                emit(emitter, "progress", Map.of("step", "ANALYZE", "percent", 10,
                        "text", "正在读取你的能力画像与差距分析…"));
                Thread.sleep(120L);
                emit(emitter, "progress", Map.of("step", "PROMPT", "percent", 25,
                        "text", "已装载提示词 path_v4，temperature=0.3（求稳定输出）"));
                Thread.sleep(80L);
                emit(emitter, "progress", Map.of("step", "GENERATE", "percent", 45,
                        "text", "正在生成阶段化路径…"));

                Map<String, Object> result = careerPathService.generate(userId, horizonYears, chaos);

                emit(emitter, "progress", Map.of("step", "VALIDATE", "percent", 80,
                        "text", "正在执行 JSON Schema 校验与业务规则二次校验（阶段数 ≥4、每阶段须有产出物）"));
                emit(emitter, "progress", Map.of("step", "PERSIST", "percent", 92,
                        "text", "校验通过，正在写入数据库…"));

                Map<String, Object> done = new LinkedHashMap<>(result);
                done.put("elapsedMs", System.currentTimeMillis() - start);
                emit(emitter, "done", done);
                emitter.complete();
            } catch (Exception e) {
                log.warn("路径生成失败 userId={} err={}", userId, e.getMessage());
                try {
                    emit(emitter, "error", Map.of("message",
                            e.getMessage() == null ? "生成失败，请稍后重试" : e.getMessage()));
                } catch (Exception ignored) {
                    // 客户端可能已断开
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/career/path")
    public ApiResponse<Map<String, Object>> getPath() {
        return ApiResponse.ok(careerPathService.getPath(AuthContext.userId()));
    }

    @GetMapping("/career/path/history")
    public ApiResponse<List<Map<String, Object>>> pathHistory() {
        return ApiResponse.ok(careerPathService.history(AuthContext.userId()));
    }

    /** 调整阶段（FR-3.3）。 */
    @PutMapping("/career/path/stages/{stageId}")
    public ApiResponse<Map<String, Object>> updateStage(@PathVariable long stageId,
                                                        @RequestBody CareerPathService.StageUpdate update) {
        return ApiResponse.ok(careerPathService.updateStage(AuthContext.userId(), stageId, update));
    }

    @PostMapping("/career/path/{pathId}/stages")
    public ApiResponse<Map<String, Object>> addStage(@PathVariable long pathId,
                                                     @RequestBody CareerPathService.StageUpdate update) {
        return ApiResponse.ok(careerPathService.addStage(AuthContext.userId(), pathId, update));
    }

    @DeleteMapping("/career/path/stages/{stageId}")
    public ApiResponse<Map<String, Object>> deleteStage(@PathVariable long stageId) {
        return ApiResponse.ok(careerPathService.deleteStage(AuthContext.userId(), stageId));
    }

    /** 重新规划（FR-3.3）：基于最新画像重算，而非随机重写。 */
    @PostMapping("/career/path/replan")
    public ApiResponse<Map<String, Object>> replan() {
        return ApiResponse.ok(careerPathService.replan(AuthContext.userId()));
    }

    // ------------------------------------------------------------------
    // 岗位匹配度（FR-3.4）
    // ------------------------------------------------------------------

    @GetMapping("/career/match")
    public ApiResponse<Map<String, Object>> match(@RequestParam(required = false) Long jobId) {
        long userId = AuthContext.userId();
        Long target = jobId != null ? jobId : perception.targetJobId(userId);
        if (target == null) {
            throw new cn.zjnu.a02.common.api.BizException(
                    cn.zjnu.a02.common.api.ErrorCode.PROFILE_INCOMPLETE, "请先设置目标岗位");
        }
        return ApiResponse.ok(matchService.match(userId, target));
    }

    private void emit(SseEmitter emitter, String event, Map<String, Object> payload) throws Exception {
        emitter.send(SseEmitter.event().name(event).data(json.stringify(payload)));
    }

    public record PathRequest(Integer horizonYears, Boolean chaos) {
    }
}
