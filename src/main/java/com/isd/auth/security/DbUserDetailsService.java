package com.isd.auth.security;

import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 把数据库用户翻译成 Spring Security 认识的 UserDetails。
 * 只要容器里同时存在 UserDetailsService 和 PasswordEncoder，Boot 就会自动装配 DaoAuthenticationProvider，
 * 我们不需要手写认证逻辑，只需要在 Controller 里调用 AuthenticationManager.authenticate(...)。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    private final UserMapper userMapper;

    public DbUserDetailsService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("用户不存在: " + username));
        return new LoginUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getFullName(),
                user.getRole(),
                Integer.valueOf(1).equals(user.getStatus()),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }
}
