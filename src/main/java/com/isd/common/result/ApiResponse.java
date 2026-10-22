package com.isd.common.result;

/**
 * 统一响应体。
 * 设计取舍：HTTP 状态码保留原有语义（2xx/4xx/5xx），body 内再给业务码 code + 可读 message。
 * 这样网关、监控、前端拦截器都能按 HTTP 状态统一处理，同时业务错误仍可细分。
 *
 * @param code    业务码，0 表示成功
 * @param message 可读提示
 * @param data    业务数据，失败时为 null
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static final int SUCCESS_CODE = 0;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(SUCCESS_CODE, "OK", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(SUCCESS_CODE, "OK", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
