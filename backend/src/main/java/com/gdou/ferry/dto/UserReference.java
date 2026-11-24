package com.gdou.ferry.dto;

import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;

/**
 * 只读用户引用。后续检票、订单和统计模块只依赖这里的安全字段。
 */
public record UserReference(
        Long id,
        String username,
        String name,
        Role role,
        UserStatus status) {

    public static UserReference from(User user) {
        return new UserReference(
                user.getId(),
                user.getUsername(),
                user.getName(),
                user.getRole(),
                user.getStatus()
        );
    }
}
