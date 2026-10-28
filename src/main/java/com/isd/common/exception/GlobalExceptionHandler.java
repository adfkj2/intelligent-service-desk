package com.isd.common.exception;

import java.util.stream.Collectors;

import com.isd.common.metrics.IsdMetrics;
import com.isd.common.result.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

    private final IsdMetrics metrics;

    public GlobalExceptionHandler(IsdMetrics metrics) {
        this.metrics = metrics;
    }

    /** 业务规则拒绝，如工单不存在、状态非法迁移 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        log.warn("业务异常: code={}, message={}", ex.errorCode().code(), ex.getMessage());
        /*
         * 只把 Service 判权产生的 403 计入 resource 层。
         * 登录禁用也复用 FORBIDDEN（为了返回 403），但那是认证结果、已经在 isd.auth.login 里记过，
         * 混进来会让"越权"指标变吵。
         */
        if (ex.errorCode() == ErrorCode.FORBIDDEN && !request.getRequestURI().startsWith("/api/auth/")) {
            metrics.recordForbidden("resource");
        }
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

    /** 401：认证失败（过滤器链之外抛出的认证异常，如手工 Authenticate 时未捕获） */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
        log.warn("认证失败: {}", ex.getMessage());
        ErrorCode code = ErrorCode.UNAUTHORIZED;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), code.defaultMessage()));
    }

    /** 403：已登录但无权限（方法级安全拒绝会走到这里，不能被兜底处理成 500） */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("访问被拒绝: {}", ex.getMessage());
        metrics.recordForbidden("method");
        ErrorCode code = ErrorCode.FORBIDDEN;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), code.defaultMessage()));
    }

    /** 缺少必填查询参数（如 /api/kb/search 不传 q）：400 而不是被兜底成 500 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("缺少必填参数: {}", ex.getParameterName());
        ErrorCode code = ErrorCode.INVALID_PARAM;
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.error(code.code(), "缺少必填参数: " + ex.getParameterName()));
    }

    /** 405：HTTP 方法不支持，如用 GET 调 POST 接口 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("请求方法不支持: {}", ex.getMessage());
        ErrorCode code = ErrorCode.INVALID_PARAM;
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(code.code(), "请求方法不支持: " + ex.getMethod()));
    }

    /** 415：Content-Type 不支持，如漏了 application/json */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        log.warn("Content-Type 不支持: {}", ex.getMessage());
        ErrorCode code = ErrorCode.INVALID_PARAM;
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(code.code(), "Content-Type 不支持，请使用 application/json"));
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
