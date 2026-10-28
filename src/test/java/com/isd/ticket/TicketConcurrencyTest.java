package com.isd.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import com.isd.ticket.service.TicketService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发接单保护测试（P2 的核心验收项）。
 *
 * 为什么这个类故意不加 @Transactional：
 * 事务是连接级别的，两个线程各自开事务才能真的并发；测试方法上挂事务会把数据锁在未提交状态，
 * 另一个线程根本读不到，测出来的是假的并发。所以这里用真实提交 + @AfterEach 手工清理。
 *
 * 怎么保证"同时"：CountDownLatch 当起跑线，两个线程都 await 到 0 才同时冲，
 * 避免线程池调度顺序导致其中一个线程提前跑完，测不出冲突。
 */
@SpringBootTest
class TicketConcurrencyTest extends AbstractIntegrationTest {

    private static final LoginUser SUPPORT_A = TestUsers.support(5001L);
    private static final LoginUser SUPPORT_B = TestUsers.support(5002L);

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private Long ticketId;

    @BeforeEach
    void insertOpenTicket() {
        String ticketNo = "T-CONC-" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update("""
                INSERT INTO ticket (ticket_no, title, description, category, priority, status, creator_id, version)
                VALUES (?, '并发送修测试', '两个线程同时接单', 'IT_FAULT', 'HIGH', 'OPEN', 9001, 0)
                """, ticketNo);
        ticketId = jdbcTemplate.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
    }

    @AfterEach
    void cleanUp() {
        if (ticketId != null) {
            jdbcTemplate.update("DELETE FROM ticket_operation_log WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket WHERE id = ?", ticketId);
        }
    }

    @Test
    @DisplayName("两个线程同时接单同一工单：恰好一个成功，另一个 409，version 只加 1")
    void concurrentClaim() throws Exception {
        double conflictsBefore = counterValue("isd.ticket.conflicts", "action", "CLAIM");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            Future<String> first = pool.submit(claimTask(SUPPORT_A, startLine));
            Future<String> second = pool.submit(claimTask(SUPPORT_B, startLine));

            // 起跑：两个线程在同一瞬间进入 claim
            startLine.countDown();

            List<String> results = new ArrayList<>();
            results.add(first.get(10, TimeUnit.SECONDS));
            results.add(second.get(10, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder("SUCCESS", "CONFLICT");
            // 失败方恰好计一次冲突：这个指标就是用来证明"乐观锁真的在工作"的
            assertThat(counterValue("isd.ticket.conflicts", "action", "CLAIM"))
                    .isEqualTo(conflictsBefore + 1);
        } finally {
            pool.shutdownNow();
        }

        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM ticket WHERE id = ?", Integer.class, ticketId);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
        Long assigneeId = jdbcTemplate.queryForObject(
                "SELECT assignee_id FROM ticket WHERE id = ?", Long.class, ticketId);
        Integer logCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_operation_log WHERE ticket_id = ?", Integer.class, ticketId);

        assertThat(version).isEqualTo(1);
        assertThat(status).isEqualTo("IN_PROGRESS");
        assertThat(assigneeId).isIn(SUPPORT_A.getId(), SUPPORT_B.getId());
        // 只有成功的那一次会写日志，失败方在写日志之前就被乐观锁拦住了
        assertThat(logCount).isEqualTo(1);
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        Counter counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0 : counter.count();
    }

    private Callable<String> claimTask(LoginUser operator, CountDownLatch startLine) {
        return () -> {
            startLine.await();
            try {
                ticketService.claim(ticketId, operator);
                return "SUCCESS";
            } catch (BusinessException ex) {
                return "CONFLICT";
            }
        };
    }
}
