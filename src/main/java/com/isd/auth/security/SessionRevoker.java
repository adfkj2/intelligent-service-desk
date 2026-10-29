package com.isd.auth.security;

import java.util.Map;

import com.isd.common.metrics.IsdMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.Session;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.stereotype.Component;

/**
 * 按登录名失效会话。
 *
 * 为什么需要：Session 方案里角色、启用状态、密码都缓存在会话中。
 * 管理员重置密码后，如果旧会话还能继续用，等于"改了密码但没踢人"，
 * 临时密码与强制改密也就形同虚设。
 *
 * 为什么按 username 而不是 userId：
 * Spring Session 的 principal 索引就是 {@code Authentication.getName()}（即 UserDetails.getUsername()），
 * userId 不在索引里。按 id 查只能 KEYS/SCAN 全量扫描，那是在请求路径上做 O(N) 操作。
 *
 * 为什么是 deleteById 而不是"标记过期"：
 * 会话现在存在 Redis，过期语义是"记录不存在"。对 SessionInformation 调 expireNow()
 * 只改内存里的临时对象，Redis 里的会话毫发无损——那种写法在集群下根本不生效。
 */
@Component
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    /**
     * 直接用 Redis 实现而不是 {@code FindByIndexNameSessionRepository<? extends Session>}：
     * 后者在启动时会被擦成 {@code ...<Session>} 去匹配，而实际 bean 是 {@code ...<RedisSession>}，
     * 泛型不协变，注入会失败（NoSuchBeanDefinitionException）。
     * 会话存储已经明确只有 Redis 一种实现，这里的耦合是显式的、可接受的。
     */
    private final RedisIndexedSessionRepository sessionRepository;
    private final IsdMetrics metrics;

    public SessionRevoker(RedisIndexedSessionRepository sessionRepository, IsdMetrics metrics) {
        this.sessionRepository = sessionRepository;
        this.metrics = metrics;
    }

    /** 失效某个用户的全部会话（重置密码时用） */
    public int revokeAll(String username) {
        return revoke(username, null, "password_reset");
    }

    /** 失效某个用户除当前会话外的其他会话（本人改密时用） */
    public int revokeOthers(String username, String keepSessionId) {
        return revoke(username, keepSessionId, "password_changed");
    }

    private int revoke(String username, String keepSessionId, String reason) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        // 走 principal 索引（Redis 里是一个 Set），只取该用户自己的会话，不扫全库
        Map<String, ? extends Session> sessions = sessionRepository.findByPrincipalName(username);

        int revoked = 0;
        for (Session session : sessions.values()) {
            if (keepSessionId != null && keepSessionId.equals(session.getId())) {
                continue;
            }
            sessionRepository.deleteById(session.getId());
            revoked++;
        }
        if (revoked > 0) {
            log.info("会话失效: username={} sessions={} reason={}", username, revoked, reason);
        }
        metrics.recordSessionRevoked(reason, revoked);
        return revoked;
    }
}
