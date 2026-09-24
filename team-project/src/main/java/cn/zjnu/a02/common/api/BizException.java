package cn.zjnu.a02.common.api;

/** 业务异常：携带 {@link ErrorCode}，由 GlobalExceptionHandler 统一转换为 {@link ApiResponse}。 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String detail) {
        super(detail == null || detail.isBlank() ? errorCode.message() : detail);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static BizException of(ErrorCode ec, String detail) {
        return new BizException(ec, detail);
    }
}
