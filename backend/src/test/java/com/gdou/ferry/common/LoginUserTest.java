package com.gdou.ferry.common;

import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginUserTest {

    private User user(Role role, UserStatus status) {
        User u = new User();
        u.setId(1L);
        u.setUsername("tester");
        u.setPassword("hashed");
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    void authoritiesCarryRolePrefix() {
        LoginUser lu = new LoginUser(user(Role.SELLER, UserStatus.ACTIVE));
        assertEquals(1, lu.getAuthorities().size());
        assertEquals("ROLE_SELLER", lu.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void onlyActiveUserIsEnabled() {
        assertTrue(new LoginUser(user(Role.ADMIN, UserStatus.ACTIVE)).isEnabled());
        assertFalse(new LoginUser(user(Role.ADMIN, UserStatus.DISABLED)).isEnabled());
        assertFalse(new LoginUser(user(Role.ADMIN, UserStatus.PENDING)).isEnabled());
        assertFalse(new LoginUser(user(Role.ADMIN, UserStatus.REJECTED)).isEnabled());
    }

    @Test
    void roleOfHandlesNull() {
        assertNull(LoginUser.roleOf(null));
        assertEquals(Role.ADMIN, LoginUser.roleOf(new LoginUser(user(Role.ADMIN, UserStatus.ACTIVE))));
    }
}
