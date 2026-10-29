package com.isd.ticket;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.isd.auth.security.LoginUser;
import com.jayway.jsonpath.JsonPath;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.dto.TicketCreationResult;
import com.isd.ticket.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 创建工单幂等（P0-2）。
 *
 * 为什么类上不加 @Transactional：并发用例需要两个线程各自真实提交，
 * 测试事务会把插入锁在未提交状态，另一个线程根本看不到冲突，测出来的是假并发。
 * 所以用唯一前缀 + @AfterEach 手工清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TicketIdempotencyTest extends AbstractIntegrationTest {

    /** 只用这两个用户名 ID 造身份，配合固定前缀清理，不会碰到演示数据 */
    private static final LoginUser EMPLOYEE = TestUsers.employee(8801L);
    private static final LoginUser OTHER_EMPLOYEE = TestUsers.employee(8802L);

    private static final String KEY_PREFIX = "itest-";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        // 不带 key 的用例没有可回收的幂等键，所以同时按标题前缀兜底
        String condition = "(idempotency_key LIKE ? OR title LIKE ?)";
        jdbcTemplate.update("DELETE FROM ticket_operation_log WHERE ticket_id IN "
                + "(SELECT id FROM ticket WHERE " + condition + ")", KEY_PREFIX + "%", KEY_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM ticket WHERE " + condition, KEY_PREFIX + "%", KEY_PREFIX + "%");
    }

    @Test
    @DisplayName("同一用户同一 Idempotency-Key 提交两次：只落一条，第二次 200 + Idempotent-Replay")
    void duplicateSubmitReturnsSameTicket() throws Exception {
        String key = KEY_PREFIX + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/api/tickets")
                        .with(user(EMPLOYEE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-打印机连不上")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/tickets")
                        .with(user(EMPLOYEE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-打印机连不上")))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();

        long firstId = idOf(first);
        assertThat(idOf(second)).isEqualTo(firstId);
        assertThat(countByKey(key)).isEqualTo(1);
        // 重放不应该再写一条 CREATE 审计
        assertThat(operationLogCount(firstId)).isEqualTo(1);
    }

    @Test
    @DisplayName("不带 Idempotency-Key：保持老行为，每次提交都新建")
    void withoutKeyCreatesEveryTime() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-无幂等键")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/tickets")
                        .with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-无幂等键")))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("不同用户用同一个 key：互不影响，各自建单")
    void sameKeyDifferentUsersAreIndependent() throws Exception {
        String key = KEY_PREFIX + UUID.randomUUID();

        MvcResult mine = mockMvc.perform(post("/api/tickets")
                        .with(user(EMPLOYEE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-甲的工单")))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult his = mockMvc.perform(post("/api/tickets")
                        .with(user(OTHER_EMPLOYEE))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-乙的工单")))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(idOf(his)).isNotEqualTo(idOf(mine));
        assertThat(countByKey(key)).isEqualTo(2);
    }

    @Test
    @DisplayName("Idempotency-Key 超长：400，不落库")
    void tooLongKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/tickets")
                        .with(user(EMPLOYEE))
                        .header("Idempotency-Key", KEY_PREFIX + "x".repeat(80))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("itest-超长key")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    @DisplayName("并发同 key：唯一索引兜底，两个请求拿到同一条工单")
    void concurrentSameKeyProducesOneTicket() throws Exception {
        String key = KEY_PREFIX + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startLine = new CountDownLatch(1);
        try {
            Future<Long> first = pool.submit(createTask(key, startLine));
            Future<Long> second = pool.submit(createTask(key, startLine));
            startLine.countDown();

            List<Long> ids = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(ids.get(0)).isEqualTo(ids.get(1));
        } finally {
            pool.shutdownNow();
        }
        assertThat(countByKey(key)).isEqualTo(1);
    }

    private Callable<Long> createTask(String key, CountDownLatch startLine) {
        return () -> {
            startLine.await();
            TicketCreationResult result = ticketService.create(
                    new CreateTicketRequest("并发建单", "两个线程同一个 key", "IT_FAULT", "MEDIUM"),
                    EMPLOYEE, key);
            return result.ticket().id();
        };
    }

    private String body(String title) {
        return """
                {"title":"%s","description":"幂等测试","category":"IT_FAULT","priority":"MEDIUM"}
                """.formatted(title);
    }

    private long idOf(MvcResult result) throws Exception {
        // JsonPath 对较小的数字返回 Integer，直接按 Long 取会 ClassCastException
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        return id.longValue();
    }

    private int countByKey(String key) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE idempotency_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }

    private int operationLogCount(long ticketId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ticket_operation_log WHERE ticket_id = ?", Integer.class, ticketId);
        return count == null ? 0 : count;
    }
}
