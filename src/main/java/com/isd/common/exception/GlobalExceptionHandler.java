package com.isd.common.exception;

import java.util.stream.Collectors;

import com.isd.common.result.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：把各类异常统一转换成 ApiResponse 结构，避免把堆栈或框架默认错误页暴露给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务规则拒绝，如工单不存在、状态非法迁移 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        log.warn("业务异常: code={}, message={}", ex.errorCode().code(), ex.getMessage());
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(ApiResponse.error(ex.errorCode().code(), ex.getMessage()));
    }

    /** @Valid 校验失败：把字段级错误拼成一条可读消息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ErrorCode code = ErrorCode.INVALID_PARAM;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), message));
    }

    /** 路径参数类型不对（如 /api/tickets/abc）、请求体 JSON 格式错误 */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        ErrorCode code = ErrorCode.INVALID_PARAM;
        log.warn("请求格式错误: {}", ex.getMessage());
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), "请求格式错误，请检查参数类型与 JSON 结构"));
    }

    /** 访问不存在的路径（Spring Boot 3.2+ 对静态资源未命中抛此异常） */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        ErrorCode code = ErrorCode.NOT_FOUND;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), "接口不存在: /" + ex.getResourcePath()));
    }

    /** 兜底：任何未预期异常都不把堆栈返回给调用方，只在服务端日志留完整信息 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), code.defaultMessage()));
    }
}
