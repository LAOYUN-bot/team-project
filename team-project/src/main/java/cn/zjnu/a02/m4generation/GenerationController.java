package cn.zjnu.a02.m4generation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
 * M4 生成层接口：学习单元、资源推荐、知识库引用、AI 伙伴对话（SSE）。
 *
 * <p>对应需求：FR-4.1 / FR-4.2 / FR-4.4。
 */
@RestController
@RequestMapping("/api")
public class GenerationController {

    private static final Logger log = LoggerFactory.getLogger(GenerationController.class);

    private final GenerationService generation;
    private final AgentChatService agentChat;
    private final cn.zjnu.a02.ai.AiService aiService;
    private final JsonUtils json;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "a02-chat-sse");
        t.setDaemon(true);
        return t;
    });

    public GenerationController(GenerationService generation, AgentChatService agentChat,
                                cn.zjnu.a02.ai.AiService aiService, JsonUtils json) {
        this.generation = generation;
        this.agentChat = agentChat;
        this.aiService = aiService;
        this.json = json;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @GetMapping("/learning/units")
    public ApiResponse<List<Map<String, Object>>> units() {
        return ApiResponse.ok(generation.units(AuthContext.userId()));
    }

    /** 为某个阶段拆解周学习单元（FR-4.1）。 */
    @PostMapping("/learning/stages/{stageId}/units")
    public ApiResponse<Map<String, Object>> generateUnits(@PathVariable long stageId) {
        return ApiResponse.ok(generation.generateUnitsForStage(AuthContext.userId(), stageId));
    }

    @GetMapping("/resources/recommend")
    public ApiResponse<Map<String, Object>> recommend(@RequestParam String skill) {
        return ApiResponse.ok(generation.recommend(AuthContext.userId(), skill));
    }

    @GetMapping("/kb/docs")
    public ApiResponse<List<Map<String, Object>>> kbDocs() {
        return ApiResponse.ok(generation.kbDocs());
    }

    /**
     * AI 伙伴对话（SSE 流式）。
     *
     * <p>事件：{@code meta}（会话信息）→ {@code token}（增量）→ {@code done}（落库结果）/{@code error}。
     */
    @PostMapping(value = "/agent/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        long userId = AuthContext.userId();
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.submit(() -> {
            try {
                long sessionId = agentChat.ensureSession(userId, request.sessionId());
                emit(emitter, "meta", Map.of("sessionId", sessionId,
                        "provider", aiService.provider(),
                        "disclaimer", "AI 生成内容仅供参考，请结合自身情况核实"));
                Map<String, Object> result = agentChat.chatStream(userId, sessionId, request.message(),
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
                log.warn("AI 对话失败 userId={} err={}", userId, e.getMessage());
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

    @GetMapping("/agent/sessions")
    public ApiResponse<List<Map<String, Object>>> sessions() {
        return ApiResponse.ok(agentChat.sessions(AuthContext.userId()));
    }

    @GetMapping("/agent/sessions/{sessionId}/messages")
    public ApiResponse<List<Map<String, Object>>> messages(@PathVariable long sessionId) {
        return ApiResponse.ok(agentChat.messages(AuthContext.userId(), sessionId));
    }

    /** 当前注入给模型的上下文（答辩时展示「不让模型猜」的证据）。 */
    @GetMapping("/agent/context")
    public ApiResponse<Map<String, Object>> context() {
        return ApiResponse.ok(agentChat.buildContext(AuthContext.userId()));
    }

    private void emit(SseEmitter emitter, String event, Map<String, Object> payload) throws Exception {
        emitter.send(SseEmitter.event().name(event).data(json.stringify(payload)));
    }

    public record ChatRequest(Long sessionId, String message) {
    }
}
