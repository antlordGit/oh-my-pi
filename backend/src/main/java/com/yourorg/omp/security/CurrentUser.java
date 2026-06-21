package com.yourorg.omp.security;

import com.yourorg.omp.entity.User;
import com.yourorg.omp.repo.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("未找到认证用户");
        }
        return users.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("认证用户不存在: " + auth.getName()));
    }

    public Long requireId() {
        return require().getId();
    }

    /** 返回当前用户的数据查询范围（三级隔离）。 */
    public DataScope scope() {
        return DataScope.of(require());
    }
}