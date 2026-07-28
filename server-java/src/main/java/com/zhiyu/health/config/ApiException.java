package com.zhiyu.health.config;

/** 业务错误出口：HTTP 状态码 + detail 文案，由 ApiExceptionHandler 统一序列化 */
public class ApiException extends RuntimeException {

    private final int status;

    public ApiException(int status, String detail) {
        super(detail);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
