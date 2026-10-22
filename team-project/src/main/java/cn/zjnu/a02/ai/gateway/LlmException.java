package cn.zjnu.a02.ai.gateway;

/**
 * 模型调用异常，带可判别的错误码。
 *
 * <p>v2.0 §3.4.2 的关键要求：**必须把「限流」与其他错误区分开**——
 * 限流是退避信号，绝不能做即时重试（会放大雪崩）。
 */
public class LlmException extends RuntimeException {

    public enum Code {
        /** 供应商限流（HTTP 429）：只能指数退避，不可即时重试 */
        RATE_LIMITED,
        /** 请求超时：可退避后重试一次 */
        TIMEOUT,
        /** 上游不可用（5xx/连接失败）：可切换备用模型 */
        UPSTREAM_UNAVAILABLE,
        /** 配置缺失（无 API Key 等）：直接降级，不重试 */
        NOT_CONFIGURED,
        /** 响应格式不符合 OpenAI 协议 */
        INVALID_RESPONSE
    }

    private final Code code;

    public LlmException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public LlmException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    /** 是否属于「瞬时错误、可退避重试」。限流**不在**此列。 */
    public boolean isTransient() {
        return code == Code.TIMEOUT || code == Code.UPSTREAM_UNAVAILABLE;
    }
}
