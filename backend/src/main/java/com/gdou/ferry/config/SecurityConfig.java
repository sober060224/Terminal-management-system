package com.gdou.ferry.config;

import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.repository.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;

/**
 * 模块一：身份认证与基于角色的访问控制。
 *
 * 认证方式为 Session（单体部署够用，不必引入 JWT），密码 BCrypt 存储。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String REMEMBER_ME_COOKIE_NAME = "FERRY_REMEMBER_ME";
    static final int REMEMBER_ME_SECONDS = 7 * 24 * 60 * 60;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JSON 登录成功后由 AuthController 显式调用 loginSuccess；此处只负责安全校验和 7 天有效期。
     */
    @Bean
    public RememberMeServices rememberMeServices(UserDetailsService userDetailsService,
                                                 @Value("${ferry.remember-me-key:}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("FERRY_REMEMBER_ME_KEY 未配置");
        }
        TokenBasedRememberMeServices services = new TokenBasedRememberMeServices(
                key, userDetailsService, TokenBasedRememberMeServices.RememberMeTokenAlgorithm.SHA256);
        services.setTokenValiditySeconds(REMEMBER_ME_SECONDS);
        services.setAlwaysRemember(true);
        services.setCookieName(REMEMBER_ME_COOKIE_NAME);
        services.setCookieCustomizer(cookie -> {
            cookie.setHttpOnly(true);
            cookie.setAttribute("SameSite", "Lax");
        });
        return services;
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                           PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /** 显式装配全局 AuthenticationManager，确保登录走的就是上面这个 provider */
    @Bean
    public AuthenticationManager authenticationManager(DaoAuthenticationProvider provider) {
        return new ProviderManager(provider);
    }

    /** 登录成功后由控制器手动把认证结果写回 Session */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           SecurityContextRepository securityContextRepository,
                                           UserRepository userRepository,
                                           RememberMeServices rememberMeServices) throws Exception {
        // 前端是单页应用，CSRF 令牌从可读的 Cookie 中取，由 axios 自动回填到请求头
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(reg -> reg
                // ---------- 公开接口 ----------
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
                // ---------- 管理后台专属 ----------
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // ---------- 售票员与管理员 ----------
                .requestMatchers("/api/seller/**").hasAnyRole("ADMIN", "SELLER")
                // ---------- 其余接口一律需要登录 ----------
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(csrfRequestHandler))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .rememberMe(remember -> remember.rememberMeServices(rememberMeServices))
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, auth) -> writeJson(res, 200, true, "登出成功", null, null))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "XSRF-TOKEN"))
            // 认证失败与权限不足由过滤器链直接短路，走不到 @RestControllerAdvice，
            // 这里必须自己拼出与业务接口一致的 Result 结构。
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                        writeJson(res, 401, false, "请先登录", null, ErrorCodes.NOT_LOGGED_IN))
                .accessDeniedHandler((req, res, e) ->
                        writeJson(res, 403, false, "当前角色没有执行该操作的权限", null, ErrorCodes.FORBIDDEN)))
            .addFilterAfter(new AuthenticatedUserValidationFilter(userRepository, rememberMeServices),
                    RememberMeAuthenticationFilter.class);

        return http.build();
    }

    private static void writeJson(HttpServletResponse res, int status, boolean success,
                                  String message, Object data, String code) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        // 提示语将来若拼进用户名，一个引号就能把响应体拆坏，先转掉。
        String safe = message.replace("\\", "\\\\").replace("\"", "\\\"");
        String codeJson = code == null ? "null" : "\"" + code + "\"";
        res.getWriter().write("{\"success\":" + success
                + ",\"message\":\"" + safe + "\""
                + ",\"data\":" + data
                + ",\"code\":" + codeJson + "}");
    }
}
