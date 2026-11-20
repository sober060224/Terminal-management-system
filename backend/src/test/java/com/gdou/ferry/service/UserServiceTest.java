package com.gdou.ferry.service;

import com.gdou.ferry.common.BizException;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder encoder;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, encoder);
    }

    @Test
    void registerHashesPasswordAndDefaultsToPassenger() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User saved = userService.register("newbie", "123456", "新乘客", "13800000000", "440100199505055557");

        assertEquals("newbie", saved.getUsername());
        assertEquals(Role.PASSENGER, saved.getRole());
        assertEquals(UserStatus.ACTIVE, saved.getStatus());
        assertNotEquals("123456", saved.getPassword());
        assertTrue(encoder.matches("123456", saved.getPassword()));
    }

    @Test
    void registerRejectsDuplicateUsernameWithConflict() {
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        BizException e = assertThrows(BizException.class,
                () -> userService.register("admin", "123456", "重复", "13800000000", null));

        assertSame(HttpStatus.CONFLICT, e.getStatus());
        verify(userRepository, never()).save(any());
    }

    @Test
    void loadUserByUsernameThrowsWhenMissing() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(org.springframework.security.core.userdetails.UsernameNotFoundException.class,
                () -> userService.loadUserByUsername("ghost"));
    }

    @Test
    void findByIdThrowsBizExceptionWhenMissing() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> userService.findById(99L));
        assertSame(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}
