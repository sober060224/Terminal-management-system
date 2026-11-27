package com.gdou.ferry.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResultTest {

    @Test
    void okCarriesDataAndSuccessFlag() {
        Result<String> r = Result.ok("hello");
        assertTrue(r.isSuccess());
        assertEquals("操作成功", r.getMessage());
        assertEquals("hello", r.getData());
    }

    @Test
    void okWithCustomMessage() {
        Result<String> r = Result.ok("注册成功", "payload");
        assertTrue(r.isSuccess());
        assertEquals("注册成功", r.getMessage());
        assertEquals("payload", r.getData());
    }

    @Test
    void failHasNoData() {
        Result<Void> r = Result.fail("用户名已存在");
        assertFalse(r.isSuccess());
        assertEquals("用户名已存在", r.getMessage());
        assertNull(r.getData());
    }
}
