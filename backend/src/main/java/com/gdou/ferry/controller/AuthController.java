package com.gdou.ferry.controller;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import org.springframework.http.HttpStatus;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.common.Result;
import com.gdou.ferry.dto.AuthForms;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.repository.UserRepository;
import com.gdou.ferry.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块一：登录、注册、当前用户信息。
 *
 * 登录走 Spring Security 的 AuthenticationManager，认证成功后手工把 SecurityContext
 * 存回 session，这样后续请求由过滤器链自动识别登录态。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final RememberMeServices rememberMeServices;
    private final UserRepository userRepository;
    private final UserService userService;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          RememberMeServices rememberMeServices,
                          UserRepository userRepository,
                          UserService userService) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.rememberMeServices = rememberMeServices;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    /**
     * 前端首屏先调一次这个接口拿 XSRF-TOKEN cookie，
     * 之后 axios 会自动带上 X-XSRF-TOKEN 头，Spring Security 的 CSRF 校验就过了。
     */
    @GetMapping("/csrf")
    public Result<Map<String, Object>> csrf(CsrfToken token) {
        // 必须把 CsrfToken 作为参数取一次：Spring Security 的 CSRF 令牌是延迟加载的，
        // 只有真正被读取时才会把 XSRF-TOKEN 写进响应 Cookie。
        // 不用这个参数的话这个接口永远不种 Cookie，前端第一次 POST 就会被 CSRF 拦下。
        return Result.ok(Map.of("ready", true, "token", token.getToken()));
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Valid @RequestBody AuthForms.RegisterRequest form) {
        return Result.ok("注册成功", profileOf(userService.register(
                form.username(), form.password(), form.name(), form.phone(), form.idCard())));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody AuthForms.LoginRequest form,
                                             HttpServletRequest request,
                                             HttpServletResponse response) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(form.username(), form.password()));
        } catch (AuthenticationException ex) {
            // 登录失败必须先退出已有身份，避免 401 后旧 Session 仍可访问接口。
            clearAuthenticationState(request, response);
            // 凭据类异常压成同一句话：口令错、用户不存在都不能区分，否则就成了账号枚举接口。
            // 但「账号禁用」不是凭据问题，保留原异常交给 GlobalExceptionHandler 回 403 + 准确提示。
            if (ex instanceof DisabledException) {
                throw ex;
            }
            if (ex instanceof AuthenticationServiceException) {
                throw new BizException("认证服务暂时不可用，请稍后重试",
                        HttpStatus.INTERNAL_SERVER_ERROR, ex, ErrorCodes.SERVER_ERROR);
            }
            throw new BizException("用户名或密码错误", HttpStatus.UNAUTHORIZED, ex, ErrorCodes.AUTH_FAILED);
        }

        // 先建出会话再换 session id，最后把授权信息写进这个新会话。
        // 顺序不能反：changeSessionId() 要求会话已存在。
        request.getSession(true);
        request.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        if (form.rememberMe()) {
            rememberMeServices.loginSuccess(request, response, authentication);
        } else {
            rememberMeServices.loginFail(request, response);
        }

        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return Result.ok("登录成功", profileOf(userRepository.findById(loginUser.getId())
                .orElseThrow(() -> new BizException("用户不存在"))));
    }

    @GetMapping("/current")
    public Result<Map<String, Object>> current(@AuthenticationPrincipal LoginUser loginUser) {
        if (loginUser == null) {
            return Result.fail("请先登录");
        }
        return Result.ok(profileOf(userRepository.findById(loginUser.getId())
                .orElseThrow(() -> new BizException("用户不存在"))));
    }

    private void clearAuthenticationState(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        rememberMeServices.loginFail(request, response);
    }

    /** 只回前端要用的字段，密码绝不外泄。 */
    private Map<String, Object> profileOf(User user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("username", user.getUsername());
        map.put("role", user.getRole() == null ? null : user.getRole().name());
        map.put("name", user.getName());
        map.put("phone", user.getPhone());
        map.put("idCard", user.getIdCard());
        map.put("status", user.getStatus() == null ? null : user.getStatus().name());
        map.put("createTime", user.getCreateTime());
        return map;
    }

}
