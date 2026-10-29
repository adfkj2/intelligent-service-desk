package com.isd.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
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
    private static final String REDIS_IMAGE = "redis:7.4-alpine";
    private static final int REDIS_PORT = 6379;

    /** 故意不关：JVM 退出后由 Testcontainers 的 Ryuk 回收，测试之间需要复用同一个容器 */
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = startMySQLIfPossible();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = startRedisIfPossible();

    @SuppressWarnings("resource")
    private static MySQLContainer<?> startMySQLIfPossible() {
        if (!dockerAvailable("MySQL")) {
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

    /**
     * 会话存在 Redis，测试也必须有 Redis。
     * 有 Docker 起容器（CI 走这条路），没有则回落本机 127.0.0.1:6379（docker compose up -d）。
     */
    @SuppressWarnings("resource")
    private static GenericContainer<?> startRedisIfPossible() {
        if (!dockerAvailable("Redis")) {
            return null;
        }
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                .withExposedPorts(REDIS_PORT);
        container.start();
        log("Redis 容器已启动：" + container.getHost() + ":" + container.getMappedPort(REDIS_PORT));
        return container;
    }

    private static boolean dockerAvailable(String target) {
        if (Boolean.parseBoolean(System.getenv("ISD_TEST_NO_DOCKER"))) {
            log("ISD_TEST_NO_DOCKER=true, " + target + " 使用本机服务");
            return false;
        }
        try {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                log("Docker not available, " + target + " 回落本机服务（application-local.yml / docker compose）");
                return false;
            }
        } catch (RuntimeException error) {
            // 探测本身失败也按"没有 Docker"处理，不要让测试因为环境问题直接挂掉
            log("Docker detection failed (" + error.getMessage() + "), " + target + " 回落本机服务");
            return false;
        }
        return true;
    }

    private static void log(String message) {
        System.out.println("[test-infra] " + message);
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        if (MYSQL != null) {
            registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
            registry.add("spring.datasource.username", MYSQL::getUsername);
            registry.add("spring.datasource.password", MYSQL::getPassword);
        }
        if (REDIS != null) {
            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        }
        // 测试用独立 namespace：不干扰本机开发时浏览器里的会话，也便于清理
        registry.add("spring.session.redis.namespace", () -> "isd:test:session");
        /*
         * 整套测试都从 127.0.0.1 发请求，按 IP 限流会把不同用例串起来（前一个用例失败 5 次，
         * 后面的登录就被锁）。这里把 IP 阈值放到很高，账号维度保持真实阈值；
         * IP 维度的逻辑由 LoginRateLimitTest 直接构造限流组件验证。
         */
        registry.add("isd.auth.login.max-failures-per-ip", () -> 1000);
    }
}
