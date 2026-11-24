package com.gdou.ferry.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下单表单的元素级校验：seats 上必须写 @Valid，否则 SeatRequest 自己的约束一条都不执行，
 * 空姓名、非法手机号会直接落库（P1-4）。
 */
class OrderFormsValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static OrderForms.SeatRequest seat(String name, String phone) {
        return new OrderForms.SeatRequest("A1", name, "440100199004041004", phone);
    }

    @Test
    void 座位元素为null时报错而不是500() {
        OrderForms.CreateOrderRequest request =
                new OrderForms.CreateOrderRequest(1L, Arrays.asList((OrderForms.SeatRequest) null));

        Set<ConstraintViolation<OrderForms.CreateOrderRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
    }

    @Test
    void 乘船人姓名为空时报错() {
        Set<ConstraintViolation<OrderForms.CreateOrderRequest>> violations =
                validator.validate(new OrderForms.CreateOrderRequest(1L, List.of(seat("", "13800000001"))));

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("姓名")));
    }

    @Test
    void 手机号格式不对时报错() {
        Set<ConstraintViolation<OrderForms.CreateOrderRequest>> violations =
                validator.validate(new OrderForms.CreateOrderRequest(1L, List.of(seat("王乘客", "123"))));

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("手机号")));
    }

    @Test
    void 超过五张票时报错() {
        List<OrderForms.SeatRequest> seats = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            seats.add(seat("乘客" + i, "13800000001"));
        }

        Set<ConstraintViolation<OrderForms.CreateOrderRequest>> violations =
                validator.validate(new OrderForms.CreateOrderRequest(1L, seats));

        assertFalse(violations.isEmpty());
        assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("最多")));
    }

    @Test
    void 合法请求没有校验错误() {
        Set<ConstraintViolation<OrderForms.CreateOrderRequest>> violations =
                validator.validate(new OrderForms.CreateOrderRequest(1L, List.of(seat("王乘客", "13800000001"))));

        assertTrue(violations.isEmpty());
    }
}