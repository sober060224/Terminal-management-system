package com.gdou.ferry.common;

import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理器，统一返回 Result 格式
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        // 业务异常自己带的错误码优先（USERNAME_EXISTS 这类），没有才按 HTTP 状态码推导
        String code = e.getCode() != null ? e.getCode() : codeFor(e.getStatus());
        return ResponseEntity.status(e.getStatus())
                .body(Result.fail(e.getMessage(), code));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldError();
        String message = first != null ? first.getDefaultMessage() : "参数校验失败";
        log.warn("参数校验失败: {}", message);
        return Result.fail(message, ErrorCodes.VALIDATION_ERROR);
    }

    /**
     * 破损 JSON（HttpMessageNotReadable）与路径/查询参数类型不对（例如 /api/orders/abc）
     * 都属调用方错误，统一 400，不要落到兜底的 500。
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("请求格式错误: {}", e.getMessage());
        String message = e instanceof MethodArgumentTypeMismatchException mismatch
                ? "请求参数格式不正确：" + mismatch.getName()
                : "请求体格式不正确，请检查 JSON";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(message, ErrorCodes.VALIDATION_ERROR));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthError(AuthenticationException e) {
        if (e instanceof BadCredentialsException) {
            log.warn("认证失败: 用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Result.fail("用户名或密码错误", ErrorCodes.AUTH_FAILED));
        }
        if (e instanceof DisabledException) {
            log.warn("认证失败: 账号已禁用");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Result.fail("账号已被禁用", ErrorCodes.FORBIDDEN));
        }
        log.warn("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Result.fail("认证失败", ErrorCodes.AUTH_FAILED));
    }

    @ExceptionHandler(ServletException.class)
    public ResponseEntity<Result<Void>> handleServletError(ServletException e) {
        if (e instanceof ErrorResponse er) {
            if (er.getStatusCode().is5xxServerError()) {
                log.error("服务端异常: {}", e.getMessage(), e);
                return ResponseEntity.status(er.getStatusCode())
                        .body(Result.fail("服务器内部错误，请稍后重试", ErrorCodes.SERVER_ERROR));
            }
            log.warn("{} {} -> {}", er.getStatusCode(), e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(er.getStatusCode())
                    .body(Result.fail(describe(er.getStatusCode().value()), codeFor(er.getStatusCode().value())));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("服务器内部错误，请稍后重试", ErrorCodes.SERVER_ERROR));
    }

    /** HTTP 状态码到错误码的映射，认证/鉴权相关的在 SecurityConfig 里单独处理 */
    private String codeFor(HttpStatus status) {
        return codeFor(status.value());
    }

    private String codeFor(int status) {
        return switch (status) {
            case 400 -> ErrorCodes.VALIDATION_ERROR;
            case 401 -> ErrorCodes.NOT_LOGGED_IN;
            case 403 -> ErrorCodes.FORBIDDEN;
            case 404 -> ErrorCodes.NOT_FOUND;
            case 409 -> ErrorCodes.CONFLICT;
            default -> ErrorCodes.SERVER_ERROR;
        };
    }

    private String describe(int status) {
        return switch (status) {
            case 404 -> "请求的接口不存在，请检查路径是否正确";
            case 405 -> "该接口不支持此请求方法";
            case 415 -> "请求的数据格式不受支持";
            default -> "请求无法处理";
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        if (isConstraintConflict(e)) {
            log.warn("数据库约束冲突: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Result.fail("数据冲突，请检查输入", ErrorCodes.CONFLICT));
        }
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail("服务器内部错误，请稍后重试", ErrorCodes.SERVER_ERROR));
    }

    private boolean isConstraintConflict(Throwable e) {
        Throwable root = e;
        for (int depth = 0; root != null && depth < 10; depth++) {
            String msg = root.getMessage();
            if (msg != null) {
                String m = msg.toLowerCase();
                if (m.contains("duplicate entry") || m.contains("constraint")
                        || m.contains("integrity constraint") || m.contains("foreign key")) {
                    return true;
                }
            }
            if (root instanceof java.sql.SQLIntegrityConstraintViolationException
                    || root instanceof org.hibernate.exception.ConstraintViolationException) {
                return true;
            }
            root = root.getCause();
        }
        return false;
    }
}
