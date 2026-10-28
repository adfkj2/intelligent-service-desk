package com.isd.ai;

import java.util.List;

import com.isd.ai.dto.KbBrief;
import com.isd.ai.dto.TicketBrief;
import com.isd.ai.tools.KnowledgeTools;
import com.isd.ai.tools.TicketTools;
import com.isd.auth.security.LoginUser;
import com.isd.common.exception.BusinessException;
import com.isd.kb.entity.KbCategory;
import com.isd.kb.entity.KbItem;
import com.isd.kb.mapper.KbCategoryMapper;
import com.isd.kb.mapper.KbItemMapper;
import com.isd.support.AbstractIntegrationTest;
import com.isd.support.TestUsers;
import com.isd.ticket.dto.CreateTicketRequest;
import com.isd.ticket.service.TicketService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具层测试（不经过模型）。
 *
 * 这是 P5 最该被测试覆盖的部分：模型结论不可控，但"工具拿到的数据是否正确、是否越权"必须确定。
 * 用例重点验证：工具从 SecurityContext 取身份，员工只能拿到自己的工单。
 */
@SpringBootTest
@Transactional
class AiToolsTest extends AbstractIntegrationTest {

    private static final LoginUser ALICE = TestUsers.employee(9101L);
    private static final LoginUser BOB = TestUsers.employee(9102L);

    @Autowired
    private TicketTools ticketTools;

    @Autowired
    private KnowledgeTools knowledgeTools;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private KbCategoryMapper categoryMapper;

    @Autowired
    private KbItemMapper itemMapper;

    @Autowired
    private MeterRegistry meterRegistry;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("工单工具：只返回当前登录用户自己的工单")
    void ticketToolOnlyReturnsOwnTickets() {
        ticketService.create(request("Alice 的打印机离线"), ALICE);
        ticketService.create(request("Bob 的 VPN 连不上"), BOB);

        loginAs(ALICE);
        List<TicketBrief> result = ticketTools.queryMyTickets(null, 10);

        assertThat(result).extracting(TicketBrief::title).containsExactly("Alice 的打印机离线");
    }

    @Test
    @DisplayName("工单工具：支持按状态过滤，非法状态会被服务层拒绝")
    void ticketToolFiltersByStatus() {
        ticketService.create(request("待处理的设备申请"), ALICE);
        loginAs(ALICE);

        assertThat(ticketTools.queryMyTickets("OPEN", 5)).hasSize(1);
        assertThat(ticketTools.queryMyTickets("CLOSED", 5)).isEmpty();
        assertThatThrownBy(() -> ticketTools.queryMyTickets("NOT_A_STATUS", 5))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("工单工具：没有登录上下文时直接失败，不会退化成查全表")
    void ticketToolRequiresLogin() {
        SecurityContextHolder.clearContext();
        double errorsBefore = toolCallCount("query_my_tickets", "error");

        assertThatThrownBy(() -> ticketTools.queryMyTickets(null, 5))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未登录");

        // 工具失败必须被单独统计：端到端 chat 指标看不出来（异常会被框架交给模型）
        assertThat(toolCallCount("query_my_tickets", "error")).isEqualTo(errorsBefore + 1);
    }

    @Test
    @DisplayName("知识库工具：按关键词命中，空关键词返回空列表")
    void knowledgeToolSearches() {
        KbCategory category = new KbCategory();
        category.setName("AI 工具测试");
        categoryMapper.insert(category);

        KbItem item = new KbItem();
        item.setCategoryId(category.getId());
        item.setTitle("打印机无法连接");
        item.setContent("重启打印服务后重新添加打印机");
        item.setTags("打印机,驱动");
        item.setCreatedBy(1L);
        itemMapper.insert(item);

        double successBefore = toolCallCount("search_knowledge_base", "success");
        double emptyBefore = toolCallCount("search_knowledge_base", "empty");

        assertThat(knowledgeTools.searchKnowledgeBase("打印机"))
                .extracting(KbBrief::title)
                .contains("打印机无法连接");

        assertThat(knowledgeTools.searchKnowledgeBase("  ")).isEmpty();

        assertThat(toolCallCount("search_knowledge_base", "success")).isEqualTo(successBefore + 1);
        assertThat(toolCallCount("search_knowledge_base", "empty")).isEqualTo(emptyBefore + 1);
    }

    /** 工具指标是 Timer：count 就是调用次数 */
    private double toolCallCount(String tool, String result) {
        Timer timer = meterRegistry.find("isd.ai.tool.calls")
                .tag("tool", tool)
                .tag("result", result)
                .timer();
        return timer == null ? 0 : timer.count();
    }

    private void loginAs(LoginUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private CreateTicketRequest request(String title) {
        return new CreateTicketRequest(title, "工具测试用", "IT_FAULT", "HIGH");
    }
}
