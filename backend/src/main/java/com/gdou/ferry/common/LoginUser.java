package com.gdou.ferry.common;

import com.gdou.ferry.entity.Role;
import com.gdou.ferry.entity.User;
import com.gdou.ferry.entity.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security 的 UserDetails 实现，关联 User 实体
 */
public class LoginUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final Role role;
    private final UserStatus status;

    public LoginUser(User user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.role = user.getRole();
        this.status = user.getStatus();
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * 从可能为 null 的 UserDetails 中安全地提取角色
     */
    public static Role roleOf(LoginUser user) {
        return user != null ? user.getRole() : null;
    }
}
