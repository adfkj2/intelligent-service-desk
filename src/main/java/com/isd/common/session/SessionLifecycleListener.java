package com.isd.common.session;

import com.isd.common.metrics.IsdMetrics;
import org.springframework.context.event.EventListener;
import org.springframework.session.events.SessionCreatedEvent;
import org.springframework.session.events.SessionDeletedEvent;
import org.springframework.session.events.SessionExpiredEvent;
import org.springframework.stereotype.Component;

/**
 * 会话生命周期的唯一埋点出口。
 *
 * 为什么不再用 Gauge 统计"当前在线会话数"：
 * 会话现在存在 Redis，进程内没有可枚举的 SessionRegistry（Spring Session 的
 * SpringSessionBackedSessionRegistry.getAllPrincipals() 直接抛 UnsupportedOperationException）。
 * 要拿精确值只能 SCAN 全键空间，那是在指标抓取路径上做 O(N) 阻塞操作。
 *
 * 所以改成"开始/结束"两个累计量：
 * <ul>
 *   <li>{@code isd_sessions_started_total}：新建会话数；</li>
 *   <li>{@code isd_sessions_ended_total{reason}}：delete（注销/踢人）与 expired（空闲超时）。</li>
 * </ul>
 * 在线数在查询侧用 created - ended 推导。已知误差：expired 依赖 Redis keyspace 事件通知，
 * 没开启 notify-keyspace-events 时不会产生，差值会偏高——这一点写在 README 的指标表里。
 *
 * 与 {@code isd_auth_session_revoked_total} 的分工：那个回答"安全动作是否生效"，这里回答"会话生命周期"。
 */
@Component
public class SessionLifecycleListener {

    public static final String REASON_DELETED = "deleted";
    public static final String REASON_EXPIRED = "expired";

    private final IsdMetrics metrics;

    public SessionLifecycleListener(IsdMetrics metrics) {
        this.metrics = metrics;
    }

    @EventListener
    public void onCreated(SessionCreatedEvent event) {
        metrics.recordSessionStarted();
    }

    @EventListener
    public void onDeleted(SessionDeletedEvent event) {
        metrics.recordSessionEnded(REASON_DELETED);
    }

    @EventListener
    public void onExpired(SessionExpiredEvent event) {
        metrics.recordSessionEnded(REASON_EXPIRED);
    }
}
