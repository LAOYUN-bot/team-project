package cn.zjnu.a02.common.api;

import java.util.Map;

/**
 * 统一响应体（架构文档 §10.2）。
 *
 * <pre>{ "code": 0, "message": "success", "data": {}, "traceId": "b7f3c1e2" }</pre>
 *
 * @param code    业务码，0 为成功
 * @param message 人类可读信息
 * @param data    业务数据
 * @param traceId 链路标识，便于答辩现场排障
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data, TraceIds.current());
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "success", null, TraceIds.current());
    }

    public static <T> ApiResponse<T> fail(ErrorCode ec, String detail) {
        String msg = (detail == null || detail.isBlank()) ? ec.message() : detail;
        return new ApiResponse<>(ec.code(), msg, null, TraceIds.current());
    }

    /** 降级提示：业务成功但 AI 通道降级，前端据此展示「结果仅供参考」标识。 */
    public static <T> ApiResponse<T> degraded(T data, String message) {
        return new ApiResponse<>(0, message, data, TraceIds.current());
    }

    /** 便捷构造：把提示信息塞进 data，避免前端判空。 */
    public static ApiResponse<Map<String, Object>> notice(String message, Map<String, Object> extra) {
        return new ApiResponse<>(0, message, extra, TraceIds.current());
    }
}
