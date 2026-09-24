package cn.zjnu.a02.common.api;

/**
 * 统一错误码（架构文档 §10.2）。
 *
 * <p>分段约定：1xxxx 参数、2xxxx 权限、3xxxx 业务、4xxxx 外部依赖、5xxxx AI。
 */
public enum ErrorCode {

    // ---- 通用 ----
    OK(0, 200, "success"),
    INTERNAL_ERROR(50000, 500, "服务器内部错误"),

    // ---- 1xxxx 参数 ----
    INVALID_ARGUMENT(10001, 400, "参数校验失败"),
    MISSING_FIELD(10002, 400, "必填字段缺失"),
    UNSUPPORTED_VALUE(10003, 400, "不支持的取值"),

    // ---- 2xxxx 权限 ----
    UNAUTHENTICATED(20001, 401, "未登录或登录已过期"),
    BAD_CREDENTIALS(20002, 401, "用户名或密码错误"),
    FORBIDDEN(20003, 403, "无权访问该资源"),
    USERNAME_TAKEN(20004, 400, "用户名已被占用"),

    // ---- 3xxxx 业务 ----
    NOT_FOUND(30001, 404, "资源不存在"),
    PROFILE_INCOMPLETE(30002, 400, "请先完成建档（基础信息 + 能力测评）"),
    NO_RADAR(30003, 404, "尚未生成能力画像，请先完成测评"),
    NO_PATH(30004, 404, "尚未生成职业路径"),
    SESSION_FINISHED(30005, 400, "该训练会话已结束"),
    INSUFFICIENT_TURNS(30006, 400, "对话轮次不足，至少完成 3 轮才能生成评分"),
    RATE_LIMITED(30007, 429, "请求过于频繁，请稍后再试"),

    // ---- 4xxxx 外部依赖 ----
    UPSTREAM_TIMEOUT(40001, 503, "数据源响应超时，请稍后重试"),
    UPSTREAM_UNAVAILABLE(40002, 503, "外部服务暂不可用"),

    // ---- 5xxxx AI ----
    AI_DEGRADED(50001, 200, "AI 服务已降级为本地模板，结果仅供参考"),
    AI_OUTPUT_INVALID(50002, 500, "AI 输出未通过结构校验且兜底失败");

    private final int code;
    private final int httpStatus;
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String message() {
        return message;
    }
}
