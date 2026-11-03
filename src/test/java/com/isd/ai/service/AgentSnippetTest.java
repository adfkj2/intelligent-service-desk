package com.isd.ai.service;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import com.isd.ai.dto.KnowledgeReference;
import com.isd.ai.tools.ToolCallContext;
import com.isd.common.time.BusinessTime;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSnippetTest {
    @Test
    void knowledgeReviewMetadataDistinguishesOverdueFutureAndUnspecifiedDates() {
        var overdue = new KnowledgeReference(1L, "VPN", "核对配置", "Windows 11，VPN客户端v3", BusinessTime.now().minusDays(1));
        assertThat(overdue.environment()).isEqualTo("Windows 11，VPN客户端v3");
        assertThat(overdue.reviewRequired()).isTrue();
        var future = new KnowledgeReference(2L, "打印机", "检查网线", "办公网络", BusinessTime.now().plusDays(1));
        assertThat(future.reviewRequired()).isFalse();
        var unspecified = new KnowledgeReference(3L, "邮箱", "核对地址", null, null);
        assertThat(unspecified.environment()).isNull();
        assertThat(unspecified.reviewDueAt()).isNull();
        assertThat(unspecified.reviewRequired()).isFalse();
        assertThat(new KnowledgeReference(4L, "兼容来源", "原接口").reviewRequired()).isFalse();
    }
    @Test
    void toolReferencesAreDeduplicatedAndClearedBetweenRequests() {
        AtomicInteger counter = new AtomicInteger();
        var references = new ConcurrentHashMap<Long, KnowledgeReference>();
        try {
            ToolCallContext.bind(counter, references);
            ToolCallContext.increment();
            ToolCallContext.reference(new KnowledgeReference(1L, "VPN", "核对配置"));
            ToolCallContext.reference(new KnowledgeReference(1L, "VPN", "核对配置"));
            assertThat(counter.get()).isEqualTo(1);
            assertThat(references).hasSize(1);
            ToolCallContext.clear();
            ToolCallContext.reference(new KnowledgeReference(2L, "打印机", "检查网线"));
            assertThat(references).hasSize(1);
            ToolCallContext.bind(counter);
            ToolCallContext.reference(new KnowledgeReference(3L, "邮箱", "核对地址"));
            assertThat(references).hasSize(1);
        } finally {
            ToolCallContext.clear();
        }
    }
    @Test
    void findsRelevantStepsPastTheFirstFourHundredCharacters() {
        String article = "背景信息与产品介绍。".repeat(80) + "VPN错误619：先核对客户端配置与网络，再记录排查结果。";
        assertThat(AgentService.relevantSnippet(article, "VPN 错误619 怎么处理"))
                .contains("VPN错误619", "核对客户端配置");
        assertThat(AgentService.relevantSnippet(article, "无关文字")).hasSizeLessThanOrEqualTo(402);
    }

    @Test
    void handlesEmptyAndShortSourcesWithoutInventingContent() {
        assertThat(AgentService.relevantSnippet(null, "VPN")).isEmpty();
        assertThat(AgentService.relevantSnippet(" ", "VPN")).isEmpty();
        assertThat(AgentService.relevantSnippet("请核对客户端配置。", null)).isEqualTo("请核对客户端配置。");
        assertThat(AiWorkspaceService.missingInformation("VPN连不上")).hasSize(4);
        assertThat(AiWorkspaceService.missingInformation("今天开始，影响三人，提示错误619，重启无效")).isEmpty();
        assertThat(AgentService.truncate("问".repeat(700), 500)).hasSize(500).endsWith("…");
        assertThat(AgentService.truncate("ab", 1)).isEqualTo("…");
        assertThat(AgentService.truncate(null, 500)).isEmpty();
        assertThat(AgentService.truncate("abc", 0)).isEmpty();
    }
}
