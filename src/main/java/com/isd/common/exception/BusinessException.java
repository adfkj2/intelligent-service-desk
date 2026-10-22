package com.isd.common.exception;

/**
 * 业务异常：由 Service 层主动抛出，表示"业务规则不允许"，而不是程序缺陷。
 * 继承 RuntimeException 的理由：Service 方法不必在签名上声明受检异常；
 * 同时 Spring 默认只对 RuntimeException 回滚事务（受检异常需显式 rollbackFor）。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
