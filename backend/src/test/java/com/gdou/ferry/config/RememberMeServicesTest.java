package com.gdou.ferry.config;

import com.gdou.ferry.common.LoginUser;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.rememberme.AbstractRememberMeServices;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RememberMeServicesTest {

    private static final String KEY = "unit-test-remember-me-key";

    @Test
    void rememberMeCookieLastsSevenDaysAndRestoresAuthentication() {
        User user = user("hashed-before");
        RememberMeServices services = services(user);

        Authentication authentication = authentication(user);
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        services.loginSuccess(loginRequest, loginResponse, authentication);

        Cookie cookie = loginResponse.getCookie(SecurityConfig.REMEMBER_ME_COOKIE_NAME);
        assertNotNull(cookie);
        assertEquals(SecurityConfig.REMEMBER_ME_SECONDS, cookie.getMaxAge());
        assertTrue(cookie.isHttpOnly());
        assertEquals("Lax", cookie.getAttribute("SameSite"));

        MockHttpServletRequest restoreRequest = new MockHttpServletRequest();
        restoreRequest.setCookies(cookie);
        Authentication restored = services.autoLogin(restoreRequest, new MockHttpServletResponse());

        assertNotNull(restored);
        assertEquals(7L, ((LoginUser) restored.getPrincipal()).getId());
    }

    @Test
    void passwordChangeInvalidatesRememberMeCookie() {
        User user = user("hashed-before");
        RememberMeServices services = services(user);

        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        services.loginSuccess(loginRequest, loginResponse, authentication(user));
        Cookie cookie = loginResponse.getCookie(SecurityConfig.REMEMBER_ME_COOKIE_NAME);

        user.setPassword("hashed-after");
        MockHttpServletRequest restoreRequest = new MockHttpServletRequest();
        restoreRequest.setCookies(cookie);
        MockHttpServletResponse restoreResponse = new MockHttpServletResponse();

        assertNull(services.autoLogin(restoreRequest, restoreResponse));
        Cookie canceled = restoreResponse.getCookie(SecurityConfig.REMEMBER_ME_COOKIE_NAME);
        assertNotNull(canceled);
        assertEquals(0, canceled.getMaxAge());
    }

    @Test
    void disabledUserCannotRestoreRememberMeCookie() {
        User user = user("hashed-before");
        RememberMeServices services = services(user);

        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        services.loginSuccess(loginRequest, loginResponse, authentication(user));
        Cookie cookie = loginResponse.getCookie(SecurityConfig.REMEMBER_ME_COOKIE_NAME);

        user.setStatus(UserStatus.DISABLED);
        MockHttpServletRequest restoreRequest = new MockHttpServletRequest();
        restoreRequest.setCookies(cookie);
        MockHttpServletResponse restoreResponse = new MockHttpServletResponse();

        assertNull(services.autoLogin(restoreRequest, restoreResponse));
        Cookie canceled = restoreResponse.getCookie(SecurityConfig.REMEMBER_ME_COOKIE_NAME);
        assertNotNull(canceled);
        assertEquals(0, canceled.getMaxAge());
    }

    @Test
    void logoutCancelsRememberMeCookie() {
        User user = user("hashed");
        RememberMeServices services = services(user);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ((LogoutHandler) services).logout(request, response, authentication(user));

        Cookie canceled = response.getCookie(SecurityConfig.REMEMBER_ME_COOKIE_NAME);
        assertNotNull(canceled);
        assertEquals(0, canceled.getMaxAge());
    }

    @Test
    void blankKeyIsRejected() {
        UserDetailsService userDetailsService = username -> new LoginUser(user("hashed"));
        assertThrows(IllegalStateException.class,
                () -> new SecurityConfig().rememberMeServices(userDetailsService, " "));
    }

    private RememberMeServices services(User user) {
        UserDetailsService userDetailsService = username -> new LoginUser(user);
        RememberMeServices services = new SecurityConfig().rememberMeServices(userDetailsService, KEY);
        ((AbstractRememberMeServices) services).afterPropertiesSet();
        return services;
    }

    private static Authentication authentication(User user) {
        LoginUser loginUser = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.getAuthorities());
    }

    private static User user(String password) {
        User user = new User();
        user.setId(7L);
        user.setUsername("seller1");
        user.setPassword(password);
        user.setName("张售票");
        user.setPhone("13800000002");
        user.setIdCard("440100199001010015");
        user.setRole(Role.SELLER);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
