package com.gdou.ferry.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常，用于在服务层抛出明确的错误信息。
 * status 决定返回的 HTTP 状态码，默认 400。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    /** 业务错误码；为 null 时由 GlobalExceptionHandler 按 HTTP 状态码推导。 */
    private final String code;

    public BizException(String message) {
        this(message, HttpStatus.BAD_REQUEST);
    }

    public BizException(String message, HttpStatus status) {
        this(message, status, null, null);
    }

    public BizException(String message, HttpStatus status, String code) {
        this(message, status, null, code);
    }

    public BizException(String message, Throwable cause) {
        this(message, HttpStatus.BAD_REQUEST, cause, null);
    }

    public BizException(String message, HttpStatus status, Throwable cause) {
        this(message, status, cause, null);
    }

    public BizException(String message, HttpStatus status, Throwable cause, String code) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
