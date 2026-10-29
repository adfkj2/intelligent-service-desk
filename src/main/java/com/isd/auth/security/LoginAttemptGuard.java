package com.isd.auth.security;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 登录失败次数限流（防撞库 / 防弱口令爆破）。
 *
 * 两个维度，都在 Redis 里做，天然支持多实例：
 * <ul>
 *   <li><b>账号维度</b>：同一用户名累计失败 N 次，锁定 M 分钟。这是主要防线——
 *       攻击者换 IP 也没用，锁定的是他要打的那个账号；</li>
 *   <li><b>IP 维度</b>：同一来源累计失败更多次才锁，用来挡住"一个 IP 扫大量账号"。
 *       阈值故意比账号维度宽：办公室 NAT 后所有人共用一个出口 IP，
 *       调太严会把正常同事一起锁死。</li>
 * </ul>
 *
 * 为什么不用内存计数：多实例部署时攻击者只要轮询打不同实例，内存计数就被摊薄了；
 * 会话已经外置到 Redis，复用同一个存储不引入新组件。
 *
 * 计数器只在"凭据错误"时累加：密码正确但账号被禁用属于另一种状态，
 * 不该把用户推向锁定（也避免管理员禁用账号后，对方反复试密码把自己锁住）。
 */
@Component
public class LoginAttemptGuard {

    private static final String USER_KEY_PREFIX = "isd:login:fail:user:";
    private static final String IP_KEY_PREFIX = "isd:login:fail:ip:";

    private final StringRedisTemplate redis;
    private final int maxFailuresPerUser;
    private final int maxFailuresPerIp;
    private final Duration lockWindow;

    public LoginAttemptGuard(StringRedisTemplate redis,
                             @Value("${isd.auth.login.max-failures-per-user:5}") int maxFailuresPerUser,
                             @Value("${isd.auth.login.max-failures-per-ip:20}") int maxFailuresPerIp,
                             @Value("${isd.auth.login.lock-minutes:15}") long lockMinutes) {
        this.redis = redis;
        this.maxFailuresPerUser = maxFailuresPerUser;
        this.maxFailuresPerIp = maxFailuresPerIp;
        this.lockWindow = Duration.ofMinutes(lockMinutes);
    }

    /**
     * 剩余锁定秒数，0 表示未锁定。
     *
     * 返回秒数而不是布尔值：错误提示里要告诉用户"还有几分钟"，
     * 否则他只会反复点击，把锁定窗口不断续期。
     */
    public long lockedSeconds(String username, String ip) {
        long byUser = remainingSeconds(USER_KEY_PREFIX + normalize(username), maxFailuresPerUser);
        long byIp = remainingSeconds(IP_KEY_PREFIX + normalize(ip), maxFailuresPerIp);
        return Math.max(byUser, byIp);
    }

    /** 凭据错误：两个维度都 +1，首次计数时设置窗口 TTL */
    public void recordFailure(String username, String ip) {
        increment(USER_KEY_PREFIX + normalize(username));
        increment(IP_KEY_PREFIX + normalize(ip));
    }

    /**
     * 登录成功：清掉账号维度的计数。
     * 故意不清 IP 计数——否则攻击者只要手里有一个正常账号，就能定期"洗白"自己的 IP。
     */
    public void recordSuccess(String username) {
        redis.delete(USER_KEY_PREFIX + normalize(username));
    }

    private long remainingSeconds(String key, int threshold) {
        String raw = redis.opsForValue().get(key);
        if (raw == null) {
            return 0;
        }
        long count;
        try {
            count = Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            // 键被别的程序写脏了：删掉重来，不要让登录入口一直报错
            redis.delete(key);
            return 0;
        }
        if (count < threshold) {
            return 0;
        }
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl < 0 ? lockWindow.toSeconds() : ttl;
    }

    private void increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 只在第一次计数时设置过期：后续失败不续期，避免攻击者靠持续尝试把窗口无限延长
            redis.expire(key, lockWindow);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
