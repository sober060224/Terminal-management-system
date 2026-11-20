package com.gdou.ferry.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BizExceptionTest {

    @Test
    void defaultStatusIsBadRequest() {
        BizException e = new BizException("参数不对");
        assertEquals("参数不对", e.getMessage());
        assertSame(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void explicitStatusIsKept() {
        BizException e = new BizException("用户名已存在", HttpStatus.CONFLICT);
        assertSame(HttpStatus.CONFLICT, e.getStatus());
    }

    @Test
    void causeIsKeptAndStatusDefaultsToBadRequest() {
        RuntimeException cause = new RuntimeException("boom");
        BizException e = new BizException("内部出错", cause);
        assertSame(cause, e.getCause());
        assertSame(HttpStatus.BAD_REQUEST, e.getStatus());
    }

    @Test
    void statusAndCauseTogether() {
        RuntimeException cause = new RuntimeException("boom");
        BizException e = new BizException("未授权", HttpStatus.UNAUTHORIZED, cause);
        assertSame(HttpStatus.UNAUTHORIZED, e.getStatus());
        assertSame(cause, e.getCause());
    }
}
