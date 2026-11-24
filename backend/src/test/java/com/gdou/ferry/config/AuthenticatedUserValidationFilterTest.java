package com.gdou.ferry.config;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.RememberMeServices;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticatedUserValidationFilterTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final RememberMeServices rememberMeServices = mock(RememberMeServices.class);
    private final AuthenticatedUserValidationFilter filter =
            new AuthenticatedUserValidationFilter(userRepository, rememberMeServices);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void activeUserWithSameRolePasses() throws Exception {
        User user = user(UserStatus.ACTIVE, Role.SELLER);
        LoginUser loginUser = new LoginUser(user);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        authenticate(loginUser);

        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, chain(called));

        assertTrue(called.get());
        assertFalse(session.isInvalid());
        assertEquals(HttpStatus.OK.value(), response.getStatus());
    }

    @Test
    void disabledUserRejectsAndInvalidatesSession() throws Exception {
        User databaseUser = user(UserStatus.DISABLED, Role.SELLER);
        LoginUser loginUser = new LoginUser(user(UserStatus.ACTIVE, Role.SELLER));
        when(userRepository.findById(7L)).thenReturn(Optional.of(databaseUser));
        authenticate(loginUser);

        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, chain(called));

        assertFalse(called.get());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        assertTrue(response.getContentAsString().contains("NOT_LOGGED_IN"));
        assertTrue(session.isInvalid());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(rememberMeServices).loginFail(request, response);
    }

    @Test
    void changedRoleRejectsOldSession() throws Exception {
        User databaseUser = user(UserStatus.ACTIVE, Role.ADMIN);
        LoginUser loginUser = new LoginUser(user(UserStatus.ACTIVE, Role.SELLER));
        when(userRepository.findById(7L)).thenReturn(Optional.of(databaseUser));
        authenticate(loginUser);

        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, chain(called));

        assertFalse(called.get());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        verify(rememberMeServices).loginFail(request, response);
    }

    @Test
    void missingUserRejectsOldSession() throws Exception {
        LoginUser loginUser = new LoginUser(user(UserStatus.ACTIVE, Role.SELLER));
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        authenticate(loginUser);

        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpSession session = (MockHttpSession) request.getSession(false);
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, chain(called));

        assertFalse(called.get());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        assertTrue(session.isInvalid());
        verify(rememberMeServices).loginFail(request, response);
    }

    @Test
    void passwordChangeRejectsOldSession() throws Exception {
        User databaseUser = user(UserStatus.ACTIVE, Role.SELLER);
        databaseUser.setPassword("hashed-after");
        LoginUser loginUser = new LoginUser(user(UserStatus.ACTIVE, Role.SELLER));
        when(userRepository.findById(7L)).thenReturn(Optional.of(databaseUser));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new RememberMeAuthenticationToken(
                "remember-me-key", loginUser, loginUser.getAuthorities()));
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, chain(called));

        assertFalse(called.get());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        verify(rememberMeServices).loginFail(request, response);
    }

    @Test
    void rememberMeAuthenticationWithChangedRoleRejects() throws Exception {
        User databaseUser = user(UserStatus.ACTIVE, Role.ADMIN);
        LoginUser loginUser = new LoginUser(user(UserStatus.ACTIVE, Role.SELLER));
        when(userRepository.findById(7L)).thenReturn(Optional.of(databaseUser));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new RememberMeAuthenticationToken(
                "remember-me-key", loginUser, loginUser.getAuthorities()));
        SecurityContextHolder.setContext(context);

        MockHttpServletRequest request = requestWithSession();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean called = new AtomicBoolean();

        filter.doFilter(request, response, chain(called));

        assertFalse(called.get());
        assertEquals(HttpStatus.UNAUTHORIZED.value(), response.getStatus());
        verify(rememberMeServices).loginFail(request, response);
    }

    private void authenticate(LoginUser loginUser) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                loginUser, null, loginUser.getAuthorities()));
        SecurityContextHolder.setContext(context);
    }

    private static MockHttpServletRequest requestWithSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/current");
        request.setRequestURI("/api/auth/current");
        request.setSession(new MockHttpSession());
        return request;
    }

    private static FilterChain chain(AtomicBoolean called) {
        return (request, response) -> called.set(true);
    }

    private static User user(UserStatus status, Role role) {
        User user = new User();
        user.setId(7L);
        user.setUsername("seller1");
        user.setPassword("hashed-password");
        user.setName("张售票");
        user.setPhone("13800000002");
        user.setIdCard("440100199001010015");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
