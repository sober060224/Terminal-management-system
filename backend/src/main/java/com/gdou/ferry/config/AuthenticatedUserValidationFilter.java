package com.gdou.ferry.config;

import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 校验 Session 中保存的状态/角色/密码快照是否仍与数据库一致。
 *
 * 普通登录和 Remember Me 自动登录都在进入授权判断前校验一次，账号被删除、
 * 禁用、改角色或改密码后，旧会话下一次请求立即失效。
 */
public class AuthenticatedUserValidationFilter extends OncePerRequestFilter {

    private static final Set<String> SKIP_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/csrf",
            "/api/auth/logout"
    );

    private final UserRepository userRepository;
    private final RememberMeServices rememberMeServices;

    public AuthenticatedUserValidationFilter(UserRepository userRepository,
                                             RememberMeServices rememberMeServices) {
        this.userRepository = userRepository;
        this.rememberMeServices = rememberMeServices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (!path.startsWith("/api/")
                || SKIP_PATHS.contains(path)
                || authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            filterChain.doFilter(request, response);
            return;
        }

        User current = userRepository.findById(loginUser.getId()).orElse(null);
        if (current == null
                || current.getStatus() != UserStatus.ACTIVE
                || current.getRole() != loginUser.getRole()
                || !current.getPassword().equals(loginUser.getPassword())) {
            reject(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        rememberMeServices.loginFail(request, response);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"请先登录\",\"data\":null,\"code\":\""
                + ErrorCodes.NOT_LOGGED_IN + "\"}");
    }
}
