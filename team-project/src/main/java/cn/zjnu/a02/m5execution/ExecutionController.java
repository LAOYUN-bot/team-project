package cn.zjnu.a02.m5execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.common.util.JsonUtils;
import cn.zjnu.a02.platform.security.AuthContext;

/**
 * M5 执行层接口：学习任务、打卡、3 个职场场景模拟训练与评分。
 *
 * <p>对应需求：FR-5.1 / FR-5.2 / FR-5.3。
 */
@RestController
@RequestMapping("/api")
public class ExecutionController {

    private static final Logger log = LoggerFactory.getLogger(ExecutionController.class);

    private final TaskService taskService;
    private final SimulationService simulation;
    private final JsonUtils json;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "a02-sim-sse");
        t.setDaemon(true);
        return t;
    });

    public ExecutionController(TaskService taskService, SimulationService simulation, JsonUtils json) {
        this.taskService = taskService;
        this.simulation = simulation;
        this.json = json;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 学习任务（FR-5.1）
    // ------------------------------------------------------------------

    @GetMapping("/learning/tasks")
    public ApiResponse<List<Map<String, Object>>> tasks(@RequestParam(required = false) String status) {
        return ApiResponse.ok(taskService.tasks(AuthContext.userId(), status));
    }

    @PostMapping("/learning/tasks/generate")
    public ApiResponse<Map<String, Object>> generateTasks(@RequestBody(required = false) GenerateRequest request) {
        return ApiResponse.ok(taskService.createFromUnits(AuthContext.userId(),
                request == null ? null : request.limit()));
    }

    @PatchMapping("/learning/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> updateTask(@PathVariable long taskId,
                                                       @RequestBody StatusRequest request) {
        return ApiResponse.ok(taskService.updateStatus(AuthContext.userId(), taskId, request.status()));
    }

    @PostMapping("/learning/checkin")
    public ApiResponse<Map<String, Object>> checkin(@RequestBody(required = false) CheckinRequest request) {
        return ApiResponse.ok(taskService.checkin(AuthContext.userId(),
                request == null ? null : request.minutes(),
                request == null ? null : request.note()));
    }

    @GetMapping("/learning/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(taskService.stats(AuthContext.userId()));
    }

    @GetMapping("/learning/heatmap")
    public ApiResponse<List<Map<String, Object>>> heatmap(@RequestParam(defaultValue = "84") int days) {
        return ApiResponse.ok(taskService.heatmap(AuthContext.userId(), days));
    }

    // ------------------------------------------------------------------
    // 模拟训练（FR-5.2 / FR-5.3）
    // ------------------------------------------------------------------

    @GetMapping("/simulation/scenarios")
    public ApiResponse<List<Map<String, Object>>> scenarios() {
        return ApiResponse.ok(simulation.scenarios());
    }

    @PostMapping("/simulation/{scenarioId}/sessions")
    public ApiResponse<Map<String, Object>> startSession(@PathVariable long scenarioId) {
        return ApiResponse.ok(simulation.startSession(AuthContext.userId(), scenarioId));
    }

    @GetMapping("/simulation/sessions")
    public ApiResponse<List<Map<String, Object>>> sessions() {
        return ApiResponse.ok(simulation.sessions(AuthContext.userId()));
    }

    @GetMapping("/simulation/sessions/{sessionId}")
    public ApiResponse<Map<String, Object>> sessionDetail(@PathVariable long sessionId) {
        return ApiResponse.ok(simulation.detail(AuthContext.userId(), sessionId));
    }

    /** 对话交互（SSE 流式）：角色扮演回复逐字返回。 */
    @PostMapping(value = "/simulation/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable long sessionId, @RequestBody MessageRequest request) {
        long userId = AuthContext.userId();
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            try {
                Map<String, Object> result = simulation.sendMessage(userId, sessionId, request.message(),
                        token -> {
                            try {
                                emit(emitter, "token", Map.of("t", token));
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                emit(emitter, "done", result);
                emitter.complete();
            } catch (Exception e) {
                log.warn("模拟对话失败 sessionId={} err={}", sessionId, e.getMessage());
                try {
                    emit(emitter, "error", Map.of("message",
                            e.getMessage() == null ? "对话失败，请稍后重试" : e.getMessage()));
                } catch (Exception ignored) {
                    // 客户端可能已断开
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    /** 生成评分并回写能力画像（FR-5.3 + D-3）。 */
    @PostMapping("/simulation/sessions/{sessionId}/evaluate")
    public ApiResponse<Map<String, Object>> evaluate(@PathVariable long sessionId) {
        return ApiResponse.ok(simulation.evaluate(AuthContext.userId(), sessionId));
    }

    private void emit(SseEmitter emitter, String event, Map<String, Object> payload) throws Exception {
        emitter.send(SseEmitter.event().name(event).data(json.stringify(payload)));
    }

    public record GenerateRequest(Integer limit) {
    }

    public record StatusRequest(String status) {
    }

    public record CheckinRequest(Integer minutes, String note) {
    }

    public record MessageRequest(String message) {
    }
}
