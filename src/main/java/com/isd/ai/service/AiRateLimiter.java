package com.isd.ai.service;

import java.time.Duration;

import com.isd.common.exception.BusinessException;
import com.isd.common.exception.ErrorCode;
import com.isd.common.metrics.IsdMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * AI 对话配额（成本护栏）。
 *
 * 为什么必须有：每次对话都要付 token 费用。没有配额时，一个脚本就能把额度刷穿，
 * 而且从监控上看起来只是"AI 用得很多"，很难区分正常高峰与滥用。
 *
 * 两个维度，都存在 Redis（与会话、登录限流同一存储，多实例天然共享）：
 * <ul>
 *   <li><b>用户维度</b>：主要防线，锁的是"谁在刷"；</li>
 *   <li><b>IP 维度</b>：阈值放宽（默认 60/min），办公室 NAT 后所有人共用一个出口，
 *       调太严会把整层同事一起挡住。</li>
 * </ul>
 *
 * 这里是"配额"而不是"失败计数"，所以采用先 INCR 再比较：count &gt; max 即拒绝。
 * 先查后加在并发下会放过超额请求（多个请求同时读到 max-1），而配额恰恰是最不该超的地方。
 * 被拒绝的请求也让计数 +1，但不会续期 TTL，窗口到期自然归零。
 *
 * 用户维度命中后不再消耗 IP 配额：否则一个人在 NAT 后面猛刷，会顺带把同事的公网 IP 配额吃光。
 */
@Component
public class AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimiter.class);

    private static final String USER_KEY_PREFIX = "isd:ai:req:user:";
    private static final String IP_KEY_PREFIX = "isd:ai:req:ip:";

    /** 指标标签取值：与 check 的两个分支一一对应，禁止再扩展成 userId/ip（高基数） */
    public static final String DIMENSION_USER = "user";
    public static final String DIMENSION_IP = "ip";

    private final StringRedisTemplate redis;
    private final IsdMetrics metrics;
    private final boolean enabled;
    private final int maxPerUser;
    private final int maxPerIp;
    private final Duration window;

    public AiRateLimiter(StringRedisTemplate redis,
                         IsdMetrics metrics,
                         @Value("${isd.ai.rate-limit.enabled:true}") boolean enabled,
                         @Value("${isd.ai.rate-limit.max-per-user:20}") int maxPerUser,
                         @Value("${isd.ai.rate-limit.max-per-ip:60}") int maxPerIp,
                         @Value("${isd.ai.rate-limit.window-seconds:60}") long windowSeconds) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
        // 阈值配成 0/负数不是"关闭限流"，而是"全部拒绝"；夹到 1 让误配置的后果可预期
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxPerIp = Math.max(1, maxPerIp);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    /**
     * 超限时抛出 429（业务码 42900），提示里带剩余秒数——只说"太频繁"用户只会继续点，
     * 带上"还有 N 秒"才能让他停下来。
     */
    public void check(Long userId, String clientIp) {
        if (!enabled) {
            return;
        }
        try {
            long userWait = hit(USER_KEY_PREFIX + (userId == null ? "anonymous" : userId), maxPerUser);
            if (userWait > 0) {
                metrics.recordAiRateLimited(DIMENSION_USER);
                throw tooMany(userWait);
            }
            long ipWait = hit(IP_KEY_PREFIX + normalize(clientIp), maxPerIp);
            if (ipWait > 0) {
                metrics.recordAiRateLimited(DIMENSION_IP);
                throw tooMany(ipWait);
            }
        } catch (DataAccessException ex) {
            /*
             * 限流是护栏，不是单点：Redis 抖动时宁可少挡几个请求，也不该让 AI 接口整体 500。
             * 不会吞掉限流本身的拒绝——tooMany 抛的是 BusinessException，不是 DataAccessException。
             * 真要收紧成 fail-close，把这里改成 rethrow 即可，但那时 Redis 已经是全站单点了。
             */
            log.warn("AI 限流计数失败，本次放行: userId={}", userId, ex);
        }
    }

    /**
     * @return 0 表示本次仍在新配额内；大于 0 表示需要等待的秒数
     */
    private long hit(String key, int max) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            // 正常不会发生（increment 返回 Long）；真的拿到 null 时按放行处理，
            // 因为"限流组件自身异常"不应该变成"所有用户都不能用 AI"
            return 0;
        }
        if (count == 1L) {
            // 只在第一次计数设置 TTL：后续请求不续期，避免持续请求把窗口无限拉长
            redis.expire(key, window);
        }
        if (count <= max) {
            return 0;
        }
        Long ttl = redis.getExpire(key);
        return ttl == null || ttl <= 0 ? window.toSeconds() : ttl;
    }

    private BusinessException tooMany(long retryAfterSeconds) {
        return new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                "AI 调用过于频繁，请 " + retryAfterSeconds + " 秒后再试");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
