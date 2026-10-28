package com.isd.auth.entity;

import java.time.LocalDateTime;

import com.isd.auth.domain.UserRole;

/**
 * 用户实体，对应 sys_user 表。
 * 注意：passwordHash 只用于认证，任何接口出参都必须转成 UserResponse，不能直接返回实体。
 */
public class User {

    private Long id;
    private String username;
    private String passwordHash;
    private String fullName;
    /** 登录页“忘记密码”展示用；管理员在个人资料中维护 */
    private String phone;
    private String email;
    private UserRole role;
    private Integer status;
    /** 管理员重置密码后置 true，强制下次登录改密 */
    private Boolean mustChangePassword;
    /** 乐观锁版本号：管理端并发修改同一用户时用它检测冲突，不对外暴露 */
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Boolean getMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(Boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
