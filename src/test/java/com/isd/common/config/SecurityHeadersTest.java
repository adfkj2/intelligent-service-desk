package com.isd.common.config;

import com.isd.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSP 的开关行为：属性打开时下发生产策略，默认（开发期）关闭。
 *
 * <p>为什么要两个 Spring 上下文：属性是装配期决定的，同一个上下文里没法既开又关。
 * 两个类共用 {@link AbstractIntegrationTest} 的容器，代价只是多一次上下文启动。
 */
@SpringBootTest(properties = "isd.security.csp.enabled=true")
@AutoConfigureMockMvc
class SecurityHeadersTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("开启后下发 CSP：脚本只允许同源，且不含 unsafe-eval")
    void sendsContentSecurityPolicyWhenEnabled() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-eval"))));
    }

    @Test
    @DisplayName("样式保留 unsafe-inline：Element Plus 弹层靠内联 style 定位")
    void allowsInlineStyleForElementPlus() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("style-src 'self' 'unsafe-inline'")));
    }
}

/**
 * 默认（开发期）不能出现 CSP：Vite HMR 需要 eval/WebSocket，Swagger UI 依赖内联脚本。
 * 这一条同时防止"为了省事全局打开 CSP 把本地调试搞坏"。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DefaultSecurityHeadersTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("未开启时不输出 CSP 响应头")
    void doesNotSendContentSecurityPolicyByDefault() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }
}
