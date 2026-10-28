package com.isd.auth.security;

import java.util.Collection;
import java.util.List;

import com.isd.auth.domain.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 登录态用户，放在 SecurityContext 里作为 Authentication 的 principal。
 *
 * 为什么要单独一个类而不是直接用 User 实体：
 * 1) 认证过程只需要 id/username/密码哈希/角色/启用状态，没必要把实体全部暴露到安全上下文；
 * 2) 权限字符串在这里集中生成（ROLE_ 前缀），避免各处手写 hasRole("ADMIN") 与 "ROLE_ADMIN" 混用。
 */
public class LoginUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final String fullName;
    private final UserRole role;
    private final boolean enabled;
    /** 管理员重置密码后为 true：登录后只允许改密，其他业务接口被拦截 */
    private final boolean mustChangePassword;

    public LoginUser(Long id, String username, String password, String fullName, UserRole role,
                     boolean enabled, boolean mustChangePassword) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
        this.enabled = enabled;
        this.mustChangePassword = mustChangePassword;
    }

    public Long getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }

    public String getFullName() {
        return fullName;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
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
        return enabled;
    }
}
