package com.gdou.ferry.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 破损 JSON 与路径参数类型错误过去会落到兜底 500，调用方看不出是自己写错了（P2-1）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 破损JSON返回400校验错误() {
        ResponseEntity<Result<Void>> response = handler.handleBadRequest(
                new HttpMessageNotReadableException("bad", new MockHttpInputMessage(new byte[0])));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCodes.VALIDATION_ERROR, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("JSON"));
    }

    @Test
    void 路径参数类型不对返回400并指出参数名() {
        ResponseEntity<Result<Void>> response = handler.handleBadRequest(
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCodes.VALIDATION_ERROR, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("id"));
    }

    @Test
    void 查询参数日期格式不对返回400并指出参数名() {
        ResponseEntity<Result<Void>> response = handler.handleBadRequest(
                new MethodArgumentTypeMismatchException("abc", LocalDate.class, "dateFrom", null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCodes.VALIDATION_ERROR, response.getBody().getCode());
        assertTrue(response.getBody().getMessage().contains("dateFrom"));
    }
}