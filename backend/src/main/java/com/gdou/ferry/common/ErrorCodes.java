package com.gdou.ferry.common;

/**
 * 统一错误码。前端按 code 分支处理，不去解析 message 文案。
 * 见 docs/api.md 第 2 节。
 */
public final class ErrorCodes {

    /** 未登录 / 会话失效 */
    public static final String NOT_LOGGED_IN = "NOT_LOGGED_IN";
    /** 用户名或密码错误 */
    public static final String AUTH_FAILED = "AUTH_FAILED";
    /** 已登录但角色无权访问 */
    public static final String FORBIDDEN = "FORBIDDEN";
    /** 用户名已被占用 */
    public static final String USERNAME_EXISTS = "USERNAME_EXISTS";
    /** 请求参数不合法 */
    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    /** 资源不存在 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 业务冲突（座位被占、状态不允许等） */
    public static final String CONFLICT = "CONFLICT";
    /** 服务器内部错误 */
    public static final String SERVER_ERROR = "SERVER_ERROR";

    private ErrorCodes() {
    }
}
