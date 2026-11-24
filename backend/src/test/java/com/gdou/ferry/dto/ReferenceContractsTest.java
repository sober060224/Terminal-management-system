package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferenceContractsTest {

    @Test
    void userReferenceFieldsStayStable() {
        assertEquals(
                List.of("id", "username", "name", "role", "status"),
                componentNames(UserReference.class)
        );
    }

    @Test
    void userReferenceSerializesOnlySafeFields() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("seller-old");
        user.setName("离职售票员");
        user.setRole(Role.SELLER);
        user.setStatus(UserStatus.DISABLED);
        user.setPassword("hashed-password");
        user.setPhone("13800138000");
        user.setIdCard("440100199001010015");

        String json = new ObjectMapper().writeValueAsString(UserReference.from(user));

        assertEquals(
                "{\"id\":7,\"username\":\"seller-old\",\"name\":\"离职售票员\",\"role\":\"SELLER\",\"status\":\"DISABLED\"}",
                json
        );
    }

    @Test
    void routeReferenceFieldsStayStable() {
        assertEquals(
                List.of("id", "routeName", "startPort", "endPort", "status"),
                componentNames(RouteReference.class)
        );
    }

    @Test
    void shipReferenceFieldsStayStable() {
        assertEquals(
                List.of("id", "shipName", "totalSeats", "shipType", "status"),
                componentNames(ShipReference.class)
        );
    }

    @Test
    void scheduleReferenceFieldsStayStable() {
        assertEquals(
                List.of("id", "routeId", "shipId", "departTime", "arriveTime",
                        "price", "status", "routeName", "shipName"),
                componentNames(ScheduleReference.class)
        );
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
