package com.isd.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 * 约定：4xx 段与 HTTP 语义对齐（400/404/409），5xx 段表示系统级错误。
 * 取舍：HTTP 状态放在枚举里，小项目一处可查；大项目通常把 HTTP 映射下沉到 web 适配层，
 * 让领域层只依赖业务码。
 */
public enum ErrorCode {

    INVALID_PARAM(40000, HttpStatus.BAD_REQUEST, "请求参数不合法"),
    NOT_FOUND(40400, HttpStatus.NOT_FOUND, "资源不存在"),
    CONFLICT(40900, HttpStatus.CONFLICT, "业务冲突"),
    INTERNAL_ERROR(50000, HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final int code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, HttpStatus httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
