package com.isd.auth.mapper;

import java.util.List;
import java.util.Optional;

import com.isd.auth.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户数据访问。
 * 所有 SQL 都用 #{} 占位符（PreparedStatement），杜绝字符串拼接导致的注入。
 *
 * 管理端的状态/角色更新一律走"带 version 的条件 UPDATE"：
 * 影响行数为 0 表示读到的数据已被别人改过，由 Service 翻译成 409。
 */
@Mapper
public interface UserMapper {

    String COLUMNS = "id, username, password_hash, full_name, phone, email, role, status, "
            + "must_change_password, version, created_at, updated_at";

    @Insert("""
            INSERT INTO sys_user (username, password_hash, full_name, role, status)
            VALUES (#{username}, #{passwordHash}, #{fullName}, #{role}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE username = #{username}")
    Optional<User> findByUsername(@Param("username") String username);

    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE id = #{id}")
    Optional<User> findById(@Param("id") Long id);

    /**
     * 加锁读，用于条件更新失败后的"最新值"判断。
     * 普通 SELECT 在 REPEATABLE READ 下仍读事务开始时的快照，
     * 看不到并发事务刚提交的结果，会把幂等场景误判成冲突。
     */
    @Select("SELECT " + COLUMNS + " FROM sys_user WHERE id = #{id} FOR UPDATE")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM sys_user ORDER BY id DESC")
    List<User> findAll();

    @Update("""
            UPDATE sys_user
            SET status = #{status}, version = version + 1, updated_at = NOW()
            WHERE id = #{id} AND version = #{version}
            """)
    int updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("version") Integer version);

    @Update("""
            UPDATE sys_user
            SET role = #{role}, version = version + 1, updated_at = NOW()
            WHERE id = #{id} AND version = #{version}
            """)
    int updateRole(@Param("id") Long id, @Param("role") String role, @Param("version") Integer version);

    /**
     * 锁定所有"启用中的管理员"行，用于串行化"至少保留一个管理员"这个跨行不变量。
     * 必须 ORDER BY id：固定加锁顺序，避免两个管理员互相降权时各锁一半而死锁。
     */
    @Select("""
            SELECT id FROM sys_user
            WHERE role = 'ADMIN' AND status = 1
            ORDER BY id
            FOR UPDATE
            """)
    List<Long> lockEnabledAdminIds();

    /**
     * 登录页公开联系方式的数据源：只取启用中的管理员，且不查 password_hash。
     * 由 Service 过滤掉手机号与邮箱都为空的账号。
     */
    @Select("""
            SELECT id, username, full_name, phone, email, role, status
            FROM sys_user
            WHERE role = 'ADMIN' AND status = 1
            ORDER BY id
            """)
    List<User> findEnabledAdmins();

    /**
     * 更新联系方式：不 bump version。
     * version 只保护角色/启用状态这两个管理端并发字段，联系方式与它们互不影响，
     * 混用会让"管理员改角色 + 本人改手机号"这种无关操作误报 409。
     */
    @Update("""
            UPDATE sys_user
            SET phone = #{phone}, email = #{email}, updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateContact(@Param("id") Long id, @Param("phone") String phone, @Param("email") String email);

    /**
     * 写入新密码哈希与强制改密标记。同样不 bump version。
     * mustChange 用 0/1 传参，避免 MyBatis 对 Boolean 的驱动差异。
     */
    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash},
                must_change_password = #{mustChange},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updatePassword(@Param("id") Long id,
                       @Param("passwordHash") String passwordHash,
                       @Param("mustChange") Integer mustChange);
}
