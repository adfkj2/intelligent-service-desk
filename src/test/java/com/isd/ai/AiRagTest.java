package com.isd.ai;

import java.util.List;

import com.isd.ai.client.LlmClient;
import com.isd.ai.tools.ToolCallContext;
import com.isd.auth.security.LoginUser;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG 召回 + AI 审计（P0-4）。
 *
 * 只验证编排契约，不评价模型答得好不好：
 * 1. 召回命中的知识确实进了提示词；
 * 2. 每次问答都落一条审计，字段与真实分支一致；
 * 3. 召回没命中时不硬塞上下文（宁可让模型按通用知识答，也别喂无关资料）。
 *
 * 这个类刻意不加 @Transactional：InnoDB 的全文索引只在行提交后才会进入检索视图，
 * 测试事务里的种子数据永远查不到，会得到"召回永远为空"的假阴性。
 * 代价是要自己清理数据（@AfterEach），用 itest- 前缀保证不碰演示数据。
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=sk-test-dummy")
@AutoConfigureMockMvc
class AiRagTest extends AbstractIntegrationTest {

    private static final LoginUser EMPLOYEE = TestUsers.employee(9701L);
    private static final String KB_TITLE = "itest-打印机离线处理步骤";
    private static final String CATEGORY = "itest-打印机";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private LlmClient llmClient;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM ai_chat_log WHERE user_id = ?", EMPLOYEE.getId());
        jdbcTemplate.update("DELETE FROM kb_item WHERE title LIKE 'itest-%'");
        jdbcTemplate.update("DELETE FROM kb_category WHERE name LIKE 'itest-%'");
    }

    @BeforeEach
    void seedKnowledge() {
        Long categoryId = firstId("SELECT id FROM kb_category WHERE name = ?", CATEGORY);
        if (categoryId == null) {
            jdbcTemplate.update("INSERT INTO kb_category (name, description) VALUES (?, ?)",
                    CATEGORY, "测试分类");
            categoryId = firstId("SELECT id FROM kb_category WHERE name = ?", CATEGORY);
        }
        jdbcTemplate.update("""
                        INSERT INTO kb_item (category_id, title, content, tags, created_by)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                categoryId, KB_TITLE,
                "打印机离线时先检查电源与网线，再重启打印服务；仍不通请联系技术支持。",
                "打印机,离线", 9701L);
    }

    @Test
    @DisplayName("召回命中：知识正文进入提示词，回答带来源标题，审计记录 kb_hits/tool_calls")
    void ragInjectsKnowledgeAndWritesAudit() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(llmClient.chat(promptCaptor.capture())).thenAnswer(invocation -> {
            // 模拟模型发起一次工具调用：计数发生在 aiExecutor 线程上，正是 ToolCallContext 要覆盖的场景
            ToolCallContext.increment();
            return "先检查电源与网线，再重启打印服务。";
        });

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"打印机连不上怎么办"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("LLM"))
                .andExpect(jsonPath("$.data.relatedTitles", hasItem(KB_TITLE)));

        assertThat(promptCaptor.getValue())
                .as("召回内容必须真的拼进提示词，否则 RAG 只是摆设")
                .contains("参考资料")
                .contains(KB_TITLE)
                .contains("检查电源与网线")
                .contains("用户问题：打印机连不上怎么办");

        long logId = latestLogId();
        assertThat(textColumn(logId, "source")).isEqualTo("LLM");
        assertThat(intColumn(logId, "kb_hits")).isEqualTo(1);
        assertThat(intColumn(logId, "tool_calls")).isEqualTo(1);
        assertThat(isSuccess(logId)).isTrue();
        assertThat(textColumn(logId, "question")).isEqualTo("打印机连不上怎么办");
        assertThat(textColumn(logId, "trace_id")).isNotBlank();
    }

    @Test
    @DisplayName("模型失败：降级答案用召回结果，审计标记 success=0 且 source=FALLBACK")
    void fallbackStillUsesRetrievedKnowledge() throws Exception {
        when(llmClient.chat(anyString())).thenThrow(new IllegalStateException("upstream 502"));

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"打印机连不上怎么办"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("FALLBACK"))
                .andExpect(jsonPath("$.data.answer").value(containsString(KB_TITLE)))
                .andExpect(jsonPath("$.data.relatedTitles", hasItem(KB_TITLE)));

        long logId = latestLogId();
        assertThat(textColumn(logId, "source")).isEqualTo("FALLBACK");
        assertThat(isSuccess(logId)).isFalse();
        assertThat(intColumn(logId, "kb_hits")).isEqualTo(1);
    }

    @Test
    @DisplayName("召回没命中：提示词就是用户原话，审计 kb_hits=0，miss 指标 +1")
    void retrievalMissKeepsPromptClean() throws Exception {
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        when(llmClient.chat(promptCaptor.capture())).thenReturn("这个问题我需要更多信息。");
        double missBefore = retrievalCount("miss");

        mockMvc.perform(post("/api/ai/chat").with(user(EMPLOYEE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"zzz 完全不相关的查询 xyz"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.relatedTitles").isEmpty());

        assertThat(promptCaptor.getValue()).isEqualTo("zzz 完全不相关的查询 xyz");
        assertThat(intColumn(latestLogId(), "kb_hits")).isZero();
        assertThat(retrievalCount("miss")).isEqualTo(missBefore + 1);
    }

    private Long firstId(String sql, String arg) {
        List<Long> ids = jdbcTemplate.queryForList(sql, Long.class, arg);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private long latestLogId() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM ai_chat_log WHERE user_id = ?", Long.class, EMPLOYEE.getId());
        assertThat(id).as("每次问答都必须落一条审计").isNotNull();
        return id;
    }

    private String textColumn(long id, String name) {
        return jdbcTemplate.queryForObject(
                "SELECT " + name + " FROM ai_chat_log WHERE id = ?", String.class, id);
    }

    private int intColumn(long id, String name) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + name + " FROM ai_chat_log WHERE id = ?", Integer.class, id);
        return value == null ? 0 : value;
    }

    private boolean isSuccess(long id) {
        Integer hit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_chat_log WHERE id = ? AND success = 1", Integer.class, id);
        return hit != null && hit == 1;
    }

    private double retrievalCount(String result) {
        Counter counter = meterRegistry.find("isd.ai.retrieval").tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
