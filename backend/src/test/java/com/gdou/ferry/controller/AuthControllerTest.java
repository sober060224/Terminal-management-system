package com.gdou.ferry.controller;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.common.ErrorCodes;
import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.common.Result;
import com.gdou.ferry.dto.AuthForms;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.UserRepository;
import com.gdou.ferry.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final SecurityContextRepository securityContextRepository = mock(SecurityContextRepository.class);
    private final RememberMeServices rememberMeServices = mock(RememberMeServices.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final AuthController controller = new AuthController(
            authenticationManager,
            securityContextRepository,
            rememberMeServices,
            userRepository,
            userService
    );

    @Test
    void currentReturnsCompleteOwnProfile() {
        User user = user();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        Result<Map<String, Object>> result = controller.current(new LoginUser(user));

        assertEquals(
                List.of("id", "username", "role", "name", "phone", "idCard", "status", "createTime"),
                new ArrayList<>(result.getData().keySet())
        );
        assertEquals("440100199001010015", result.getData().get("idCard"));
    }

    @Test
    void loginWithRememberMeIssuesCookie() {
        User user = user();
        LoginUser loginUser = new LoginUser(user);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authentication);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login(new AuthForms.LoginRequest("seller1", "secret", true), request, response);

        verify(rememberMeServices).loginSuccess(request, response, authentication);
    }

    @Test
    void loginWithoutRememberMeClearsOldCookie() {
        User user = user();
        LoginUser loginUser = new LoginUser(user);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities());
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authentication);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.login(new AuthForms.LoginRequest("seller1", "secret", false), request, response);

        verify(rememberMeServices).loginFail(request, response);
    }

    @Test
    void failedLoginClearsExistingSessionAndRememberMe() {
        when(authenticationManager.authenticate(any(Authentication.class)))
                .thenThrow(new BadCredentialsException("bad credentials"));

        LoginUser previousUser = new LoginUser(user());
        SecurityContext previousContext = SecurityContextHolder.createEmptyContext();
        previousContext.setAuthentication(new UsernamePasswordAuthenticationToken(
                previousUser, null, previousUser.getAuthorities()));
        SecurityContextHolder.setContext(previousContext);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        BizException error = assertThrows(BizException.class,
                () -> controller.login(new AuthForms.LoginRequest("seller1", "wrong", false), request, response));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertEquals(ErrorCodes.AUTH_FAILED, error.getCode());
        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(rememberMeServices).loginFail(request, response);
    }

    @Test
    void internalAuthenticationFailureReturnsServerError() {
        InternalAuthenticationServiceException cause =
                new InternalAuthenticationServiceException("database unavailable");
        when(authenticationManager.authenticate(any(Authentication.class))).thenThrow(cause);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        BizException error = assertThrows(BizException.class,
                () -> controller.login(new AuthForms.LoginRequest("seller1", "secret", false), request, response));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatus());
        assertEquals(ErrorCodes.SERVER_ERROR, error.getCode());
        assertEquals(cause, error.getCause());
        verify(rememberMeServices).loginFail(request, response);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setUsername("seller1");
        user.setPassword("hashed-password");
        user.setName("张售票");
        user.setPhone("13800000002");
        user.setIdCard("440100199001010015");
        user.setRole(Role.SELLER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
