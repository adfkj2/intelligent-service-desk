package com.isd.common;

import com.isd.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * traceId 是排障的入口：没带就得生成，带了就得沿用它，且不能把客户端传的东西原样塞进日志。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TraceIdFilterTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("请求未带 X-Request-Id 时生成 8 位并回写响应头")
    void generatesTraceIdWhenAbsent() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Request-Id");
        assertThat(traceId).hasSize(8);
    }

    @Test
    @DisplayName("请求带了合法 X-Request-Id 时原样透传，便于和网关/前端日志对齐")
    void echoesProvidedTraceId() throws Exception {
        mockMvc.perform(get("/api/ping").header("X-Request-Id", "abc12345"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "abc12345"));
    }

    @Test
    @DisplayName("超长或含非法字符的 id 被丢弃并重新生成，不让请求头污染日志")
    void rejectsUnsafeTraceId() throws Exception {
        String oversized = "a".repeat(80);

        MvcResult result = mockMvc.perform(get("/api/ping").header("X-Request-Id", oversized))
                .andExpect(status().isOk())
                .andReturn();

        String traceId = result.getResponse().getHeader("X-Request-Id");
        assertThat(traceId).hasSize(8);
        assertThat(traceId).isNotEqualTo(oversized);
    }
}
