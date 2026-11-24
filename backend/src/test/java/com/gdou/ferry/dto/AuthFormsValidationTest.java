package com.gdou.ferry.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFormsValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void usernameMustBeFourToTwentyCharacters() {
        assertFalse(validator.validate(form("abc")).isEmpty());
        assertTrue(validator.validate(form("abcd")).isEmpty());
        assertTrue(validator.validate(form("a".repeat(20))).isEmpty());
        assertFalse(validator.validate(form("a".repeat(21))).isEmpty());
    }

    private AuthForms.RegisterRequest form(String username) {
        return new AuthForms.RegisterRequest(
                username,
                "123456",
                "测试乘客",
                "13800138000",
                "440100199001010015"
        );
    }
}
