package com.isd.auth.security;

import com.isd.common.metrics.IsdMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;

/**
 * 按用户 id 失效会话。
 *
 * 为什么需要：Session 方案里角色、启用状态、密码都缓存在会话中。
 * 管理员重置密码后，如果旧会话还能继续用，等于“改了密码但没踢人”，
 * 临时密码与强制改密也就形同虚设。
 *
 * 实现依赖 SecurityConfig 中的 SessionRegistry；手工登录必须显式 registerNewSession，
 * 否则这里查不到会话。
 */
@Component
public class SessionRevoker {

    private static final Logger log = LoggerFactory.getLogger(SessionRevoker.class);

    private final SessionRegistry sessionRegistry;
    private final IsdMetrics metrics;

    public SessionRevoker(SessionRegistry sessionRegistry, IsdMetrics metrics) {
        this.sessionRegistry = sessionRegistry;
        this.metrics = metrics;
    }

    /** 失效某个用户的全部会话（重置密码时用） */
    public int revokeAll(Long userId) {
        return revoke(userId, null, "password_reset");
    }

    /** 失效某个用户除当前会话外的其他会话（本人改密时用） */
    public int revokeOthers(Long userId, String currentSessionId) {
        return revoke(userId, currentSessionId, "password_changed");
    }

    private int revoke(Long userId, String keepSessionId, String reason) {
        int revoked = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof LoginUser loginUser) || !loginUser.getId().equals(userId)) {
                continue;
            }
            for (SessionInformation session : sessionRegistry.getAllSessions(principal, false)) {
                if (keepSessionId != null && keepSessionId.equals(session.getSessionId())) {
                    continue;
                }
                session.expireNow();
                revoked++;
            }
        }
        if (revoked > 0) {
            log.info("会话失效: userId={} sessions={} reason={}", userId, revoked, reason);
        }
        metrics.recordSessionRevoked(reason, revoked);
        return revoked;
    }
}
