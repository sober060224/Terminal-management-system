package com.gdou.ferry.service;

import com.gdou.ferry.dto.UserReference;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import com.gdou.ferry.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:user-directory;MODE=MySQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=USER",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserDirectoryJpaRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void batchLookupReturnsOnlyRequestedUsersSortedById() {
        User active = save("active-seller", Role.SELLER, UserStatus.ACTIVE);
        User disabled = save("disabled-seller", Role.SELLER, UserStatus.DISABLED);
        save("unrequested-pending-seller", Role.SELLER, UserStatus.PENDING);
        save("unrequested-passenger", Role.PASSENGER, UserStatus.ACTIVE);

        UserDirectoryService directory = new UserDirectoryService(userRepository);
        List<Long> requestedIds = List.of(disabled.getId(), active.getId());

        assertEquals(List.of(active.getId(), disabled.getId()),
                directory.findAllByIds(requestedIds).stream()
                        .map(UserReference::id)
                        .toList());
    }

    @Test
    void roleLookupReturnsOnlySellersIncludingNonActive() {
        User active = save("active-seller", Role.SELLER, UserStatus.ACTIVE);
        User disabled = save("disabled-seller", Role.SELLER, UserStatus.DISABLED);
        User pending = save("pending-seller", Role.SELLER, UserStatus.PENDING);
        User rejected = save("rejected-seller", Role.SELLER, UserStatus.REJECTED);
        save("passenger", Role.PASSENGER, UserStatus.ACTIVE);
        save("admin", Role.ADMIN, UserStatus.ACTIVE);

        UserDirectoryService directory = new UserDirectoryService(userRepository);

        assertEquals(List.of(active.getId(), disabled.getId(), pending.getId(), rejected.getId()),
                directory.findByRole(Role.SELLER).stream()
                        .map(UserReference::id)
                        .toList());
    }

    private User save(String username, Role role, UserStatus status) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("hashed-password");
        user.setName(username);
        user.setPhone("13800138000");
        user.setIdCard("440100199001010015");
        user.setRole(role);
        user.setStatus(status);
        return userRepository.saveAndFlush(user);
    }
}
