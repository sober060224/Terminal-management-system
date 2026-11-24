package com.gdou.ferry.service;

import com.gdou.ferry.dto.UserReference;
import com.gdou.ferry.entity.Role;
import com.gdou.ferry.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 模块四、五共用的只读用户目录。
 *
 * 用户目录按用户 ID 返回安全引用，包含已禁用用户；不会暴露手机号、身份证号或密码，
 * 也不提供任何写入入口。历史业务记录显示姓名时使用用户当前姓名。
 */
@Service
@Transactional(readOnly = true)
public class UserDirectoryService {

    private final UserRepository userRepository;

    public UserDirectoryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<UserReference> findById(Long id) {
        return userRepository.findById(id).map(UserReference::from);
    }

    public List<UserReference> findAllByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllByIdInOrderByIdAsc(ids).stream()
                .map(UserReference::from)
                .toList();
    }

    public List<UserReference> findByRole(Role role) {
        return userRepository.findAllByRoleOrderByIdAsc(role).stream()
                .map(UserReference::from)
                .toList();
    }
}
