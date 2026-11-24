package com.gdou.ferry.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFormsValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void loginRequestDefaultsRememberMeToFalse() throws Exception {
        AuthForms.LoginRequest form = new JsonMapper().readValue(
                "{\"username\":\"admin\",\"password\":\"admin123\"}",
                AuthForms.LoginRequest.class);

        assertFalse(form.rememberMe());
    }

    @Test
    void usernameMustBeFourToTwentyCharacters() {
        assertFalse(validator.validate(form("abc")).isEmpty());
        assertTrue(validator.validate(form("abcd")).isEmpty());
        assertTrue(validator.validate(form("a".repeat(20))).isEmpty());
        assertFalse(validator.validate(form("a".repeat(21))).isEmpty());
    }

    @Test
    void loginUsernameMustAlsoBeFourToTwentyCharacters() {
        assertFalse(validator.validate(loginForm("abc")).isEmpty());
        assertTrue(validator.validate(loginForm("abcd")).isEmpty());
        assertTrue(validator.validate(loginForm("a".repeat(20))).isEmpty());
        assertFalse(validator.validate(loginForm("a".repeat(21))).isEmpty());
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

    private AuthForms.LoginRequest loginForm(String username) {
        return new AuthForms.LoginRequest(username, "123456", false);
    }
}
