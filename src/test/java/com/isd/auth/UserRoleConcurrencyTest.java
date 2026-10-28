package com.isd.auth;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.isd.auth.domain.UserRole;
import com.isd.auth.entity.User;
import com.isd.auth.mapper.UserMapper;
import com.isd.auth.service.UserService;
import com.isd.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端并发用例。
 *
 * 刻意不加 @Transactional：事务是连接级的，两个线程必须各自提交才能测到真实的版本冲突。
 * 数据用唯一前缀创建，@AfterEach 清理。
 */
@SpringBootTest
class UserRoleConcurrencyTest extends AbstractIntegrationTest {

    private static final String PREFIX = "conc_role_";
    private static final String TARGET = PREFIX + "target";

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long targetId;

    @BeforeEach
    void setUp() {
        cleanUp();
        User user = new User();
        user.setUsername(TARGET);
        user.setPasswordHash("{noop}password123");
        user.setFullName("并发测试目标");
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(1);
        userMapper.insert(user);
        targetId = user.getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("两个管理员同时把同一用户设为 SUPPORT：都成功且 version 只加 1")
    void concurrentSameRoleIsIdempotent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Boolean> task = () -> {
            start.await();
            try {
                userService.updateRole(targetId, "SUPPORT", 9101L);
                return true;
            } catch (RuntimeException ex) {
                return false;
            }
        };

        Future<Boolean> first = pool.submit(task);
        Future<Boolean> second = pool.submit(task);
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        User latest = userMapper.findById(targetId).orElseThrow();
        assertThat(latest.getRole()).isEqualTo(UserRole.SUPPORT);
        // 无论谁先到，最终只应发生一次有效更新；另一次要么走幂等分支、要么被 version 拦住
        assertThat(latest.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("用过期 version 更新角色：影响行数为 0")
    void staleVersionUpdateIsRejected() {
        int first = userMapper.updateRole(targetId, "SUPPORT", 0);
        int second = userMapper.updateRole(targetId, "ADMIN", 0);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(userMapper.findById(targetId).orElseThrow().getRole())
                .isEqualTo(UserRole.SUPPORT);
    }

    private void cleanUp() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE username LIKE ?", PREFIX + "%");
    }
}
