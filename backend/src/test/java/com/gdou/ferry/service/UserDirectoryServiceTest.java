package com.gdou.ferry.service;

import com.gdou.ferry.dto.UserReference;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserDirectoryServiceTest {

    private UserRepository userRepository;
    private UserDirectoryService directory;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        directory = new UserDirectoryService(userRepository);
    }

    @Test
    void batchResolvesDisabledUserAndDoesNotExposeSensitiveFields() {
        User user = user(7L, "seller-old", "离职售票员", Role.SELLER, UserStatus.DISABLED);
        when(userRepository.findAllByIdInOrderByIdAsc(List.of(7L))).thenReturn(List.of(user));

        List<UserReference> result = directory.findAllByIds(List.of(7L));

        assertEquals(1, result.size());
        assertEquals(7L, result.get(0).id());
        assertEquals("seller-old", result.get(0).username());
        assertEquals("离职售票员", result.get(0).name());
        assertEquals(Role.SELLER, result.get(0).role());
        assertEquals(UserStatus.DISABLED, result.get(0).status());
        assertEquals(
                List.of("id", "username", "name", "role", "status"),
                Arrays.stream(UserReference.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList()
        );
    }

    @Test
    void roleQueryMapsUsersWithoutFilteringStatus() {
        User admin = user(1L, "admin", "管理员", Role.ADMIN, UserStatus.DISABLED);
        when(userRepository.findAllByRoleOrderByIdAsc(Role.ADMIN)).thenReturn(List.of(admin));

        List<UserReference> result = directory.findByRole(Role.ADMIN);

        assertEquals(1, result.size());
        assertEquals(UserStatus.DISABLED, result.get(0).status());
    }

    @Test
    void missingUserReturnsEmptyReference() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertTrue(directory.findById(99L).isEmpty());
    }

    @Test
    void emptyBatchDoesNotQueryDatabase() {
        assertTrue(directory.findAllByIds(List.of()).isEmpty());
        verifyNoInteractions(userRepository);
    }

    private static User user(Long id, String username, String name, Role role, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setName(name);
        user.setRole(role);
        user.setStatus(status);
        user.setPassword("hashed-password");
        user.setPhone("13800138000");
        user.setIdCard("440100199001010015");
        return user;
    }
}
