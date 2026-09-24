package cn.zjnu.a02.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cn.zjnu.a02.common.api.ApiResponse;
import cn.zjnu.a02.common.api.BizException;
import cn.zjnu.a02.common.api.ErrorCode;

/**
 * 全局异常兜底（架构文档 §10.2）：所有异常都转换为统一响应体，
 * 绝不把堆栈抛给调用方（尤其是 AI 工具链）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        ErrorCode ec = ex.errorCode();
        log.warn("业务异常 code={} msg={}", ec.code(), ex.getMessage());
        return ResponseEntity.status(ec.httpStatus()).body(ApiResponse.fail(ec, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数异常: {}", ex.getMessage());
        return ResponseEntity.status(400)
                .body(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(500)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "服务器内部错误，请稍后重试"));
    }
}
