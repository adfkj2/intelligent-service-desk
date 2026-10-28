package com.isd.ai.tools;

import java.util.List;

import com.isd.ai.dto.TicketBrief;
import com.isd.auth.security.CurrentUserProvider;
import com.isd.auth.security.LoginUser;
import com.isd.ticket.service.TicketService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供模型调用的工单工具（只读）。
 *
 * 安全要点（面试必讲）：
 * 1. 工具签名里**没有 userId 参数** —— 身份只能来自 SecurityContext。
 *    否则模型（或用户通过提示注入）只要在参数里写 uid=1，就能查到别人的工单；
 * 2. 工具不直接碰 Mapper，走 TicketService 复用同一套资源级权限：
 *    EMPLOYEE 的角色在 Service 里被强制加上 creator_id 过滤；
 * 3. 只读：不提供创建/接单/关闭工具，写操作一律引导用户去界面完成。
 */
@Component
public class TicketTools {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final TicketService ticketService;
    private final CurrentUserProvider currentUserProvider;

    public TicketTools(TicketService ticketService, CurrentUserProvider currentUserProvider) {
        this.ticketService = ticketService;
        this.currentUserProvider = currentUserProvider;
    }

    @Tool(name = "query_my_tickets",
            description = "查询当前登录用户可见的工单列表，返回工单号、标题、状态、优先级、处理人和更新时间。"
                    + "员工只能看到自己提交的工单，技术支持和管理员可以看到全部。"
                    + "当用户询问「我的工单进展」「处理到哪一步了」「有哪些待处理的单」时调用。")
    public List<TicketBrief> queryMyTickets(
            @ToolParam(description = "按状态过滤，可选值：OPEN、IN_PROGRESS、RESOLVED、CLOSED；不传表示全部",
                    required = false) String status,
            @ToolParam(description = "返回条数上限，默认 5，最大 20", required = false) Integer limit) {

        LoginUser operator = currentUserProvider.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ticketService.list(status, null, null, 1, size, operator)
                .items()
                .stream()
                .map(TicketBrief::from)
                .toList();
    }
}
