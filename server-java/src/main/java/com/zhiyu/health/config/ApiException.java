package com.zhiyu.health.config;

/** 业务错误出口：HTTP 状态码 + detail 文案，由 ApiExceptionHandler 统一序列化 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String detail) {
        this(status, null, detail);
    }

    public ApiException(int status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
