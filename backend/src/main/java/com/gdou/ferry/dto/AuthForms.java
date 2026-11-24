package com.gdou.ferry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 认证相关的表单 DTO
 */
public class AuthForms {

    /**
     * 注册请求
     */
    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 4, max = 20, message = "用户名长度为4-20字符")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 50, message = "密码长度为6-50字符")
            String password,

            @NotBlank(message = "姓名不能为空")
            String name,

            @NotBlank(message = "手机号不能为空")
            @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
            String phone,

            @NotBlank(message = "身份证号不能为空")
            @Size(min = 18, max = 18, message = "身份证号必须为18位")
            String idCard
    ) {}

    /**
     * 登录请求
     */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 4, max = 20, message = "用户名长度为4-20字符")
            String username,

            @NotBlank(message = "密码不能为空")
            String password,

            Boolean rememberMe
    ) {
        public LoginRequest {
            rememberMe = Boolean.TRUE.equals(rememberMe);
        }
    }
}
