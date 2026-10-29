package com.isd.notification;

import java.util.Objects;

import com.isd.notification.entity.OutboxEvent;
import com.isd.notification.mapper.OutboxEventMapper;
import com.isd.notification.service.NotificationRelayScheduler;
import com.isd.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发件箱投递的核心约束：幂等、失败有上限。
 *
 * 这两个性质不能靠"代码看起来对"来保证，必须真的把唯一索引跑一遍：
 * 测试里把同一条事件重复投递（模拟多实例抢到同一批、或提交后进程崩溃再重试），
 * 断言每人仍然只有一条通知；再喂一条坏 payload，断言它最终转 FAILED 而不是永远 PENDING。
 *
 * 测试内部不自己开事务，而是复用测试方法的事务：调度器的写入与本方法的断言在同一连接上，
 * 断言才看得到未提交的数据；测试结束整体回滚，不污染开发库。
 */
@SpringBootTest(properties = {
        // 开发库可能残留真实 PENDING 事件，把批量放大，保证本用例的事件一定在批内
        "isd.notification.relay.batch-size=1000",
        "isd.notification.relay.max-attempts=5"
})
@Transactional
class NotificationRelayTest extends AbstractIntegrationTest {

    private static final long USER_A = 9101L;
    private static final long USER_B = 9102L;

    @Autowired
    private NotificationRelayScheduler relayScheduler;

    @Autowired
    private OutboxEventMapper outboxEventMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("同一事件重复投递：每个接收人只落一条（唯一索引兜底）")
    void duplicateDeliveryIsIdempotent() {
        // 接收人刻意重复一次，模拟生产者没去重或消费端重复消费
        Long eventId = insertPendingEvent("""
                {"ticketId":1,"title":"工单状态变更","content":"已解决","recipientIds":[%d,%d,%d]}
                """.formatted(USER_A, USER_A, USER_B));

        relayScheduler.relayPendingEvents();

        assertThat(notificationCount(eventId, USER_A)).isEqualTo(1);
        assertThat(notificationCount(eventId, USER_B)).isEqualTo(1);
        assertThat(statusOf(eventId)).isEqualTo(OutboxEvent.STATUS_SENT);

        // 再把事件改回 PENDING 模拟"上一次提交后进程崩溃，SENT 没写上"
        jdbcTemplate.update("UPDATE outbox_event SET status = 'PENDING' WHERE id = ?", eventId);
        relayScheduler.relayPendingEvents();

        assertThat(notificationCount(eventId, USER_A)).isEqualTo(1);
        assertThat(notificationCount(eventId, USER_B)).isEqualTo(1);
        assertThat(statusOf(eventId)).isEqualTo(OutboxEvent.STATUS_SENT);
    }

    @Test
    @DisplayName("坏 payload：重试到上限后转 FAILED，不会无限重试占住队头")
    void poisonEventStopsAfterMaxAttempts() {
        Long eventId = insertPendingEvent("{ not a valid json");

        // 调度器每轮重试一次；超过上限后该事件不再被取出
        for (int round = 0; round < 6; round++) {
            relayScheduler.relayPendingEvents();
        }

        assertThat(statusOf(eventId)).isEqualTo(OutboxEvent.STATUS_FAILED);
        Integer attempts = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT attempts FROM outbox_event WHERE id = ?", Integer.class, eventId));
        assertThat(attempts).isEqualTo(5);

        String lastError = jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_event WHERE id = ?", String.class, eventId);
        assertThat(lastError).isNotNull().contains("payload");
    }

    private Long insertPendingEvent(String payload) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("TICKET_STATUS_CHANGED");
        event.setAggregateType("TICKET");
        event.setAggregateId(1L);
        event.setPayload(payload);
        event.setStatus(OutboxEvent.STATUS_PENDING);
        outboxEventMapper.insert(event);
        assertThat(event.getId()).isNotNull();
        return event.getId();
    }

    private int notificationCount(Long eventId, long userId) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE event_id = ? AND user_id = ?",
                Integer.class, eventId, userId));
    }

    private String statusOf(Long eventId) {
        return jdbcTemplate.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String.class, eventId);
    }
}
