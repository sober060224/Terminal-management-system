package com.gdou.ferry.common;

/**
 * 统一响应体，沿用 { success, message, data } 风格
 */
public class Result<T> {

    private boolean success;
    private String message;
    private T data;
    /** 失败时的错误码，成功时为 null。字段名与 docs/api.md 一致。 */
    private String code;

    public Result() {
    }

    public Result(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public Result(boolean success, String message, T data, String code) {
        this.success = success;
        this.message = message;
        this.data = data;
        this.code = code;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(true, "操作成功", data);
    }

    public static <T> Result<T> ok(String message, T data) {
        return new Result<>(true, message, data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(false, message, null);
    }

    public static <T> Result<T> fail(String message, String code) {
        return new Result<>(false, message, null, code);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
