package com.isd.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试的数据库来源，双模式：
 *
 * <ul>
 *   <li><b>有 Docker</b>：启动一个 MySQL 8 容器，Flyway 在上下文启动时建表，测试之间互不干扰；</li>
 *   <li><b>没有 Docker</b>：不注册任何属性，回落到 {@code spring.datasource.*}（本机 MySQL80 + application-local.yml）。</li>
 * </ul>
 *
 * 容器用静态字段只启动一次：Spring 上下文会缓存复用，如果容器跟着测试类停止，
 * 下一个复用同一上下文的测试类就会连不上数据库。
 *
 * 想强制走本机 MySQL（例如临时没装 Docker、又不想等探测）：设置环境变量
 * {@code ISD_TEST_NO_DOCKER=true}。
 */
public abstract class AbstractIntegrationTest {

    /** 与 README 声明的 MySQL 8.0 对齐；不要用 latest，镜像漂移会让测试结果不可复现 */
    private static final String MYSQL_IMAGE = "mysql:8.0.36";

    /** 故意不关：JVM 退出后由 Testcontainers 的 Ryuk 回收，测试之间需要复用同一个容器 */
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = startContainerIfPossible();

    @SuppressWarnings("resource")
    private static MySQLContainer<?> startContainerIfPossible() {
        if (Boolean.parseBoolean(System.getenv("ISD_TEST_NO_DOCKER"))) {
            log("ISD_TEST_NO_DOCKER=true, using local MySQL");
            return null;
        }
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                log("Docker not available, falling back to local MySQL (application-local.yml)");
                return null;
            }
        } catch (RuntimeException error) {
            // 探测本身失败也按"没有 Docker"处理，不要让测试因为环境问题直接挂掉
            log("Docker detection failed (" + error.getMessage() + "), falling back to local MySQL");
            return null;
        }

        MySQLContainer<?> container = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
                .withDatabaseName("isd")
                .withUsername("isd")
                .withPassword("isd-test");
        container.start();
        log("MySQL 容器已启动：" + container.getJdbcUrl());
        return container;
    }

    private static void log(String message) {
        System.out.println("[test-db] " + message);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (MYSQL == null) {
            return;
        }
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
