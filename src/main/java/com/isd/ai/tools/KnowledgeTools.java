package com.isd.ai.tools;

import java.util.List;

import com.isd.ai.dto.KbBrief;
import com.isd.ai.dto.KnowledgeReference;
import com.isd.ai.service.AgentService;
import com.isd.common.metrics.IsdMetrics;
import com.isd.kb.service.KbService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供模型调用的知识库工具（只读）。
 * 关键词检索走 KbService，和 REST 接口共用同一段 LIKE 检索逻辑，避免"AI 查到的和页面查到的不一样"。
 */
@Component
public class KnowledgeTools {

    private static final int LIMIT = 3;
    private static final String TOOL_NAME = "search_knowledge_base";

    private final KbService kbService;
    private final IsdMetrics metrics;

    public KnowledgeTools(KbService kbService, IsdMetrics metrics) {
        this.kbService = kbService;
        this.metrics = metrics;
    }

    @Tool(name = "search_knowledge_base",
            description = "按关键词检索企业 IT 知识库，返回最相关条目的 id、标题、分类、正文片段、适用环境与复核期限。"
                    + "当用户描述具体故障现象（如打印机离线、VPN 连不上、密码重置）时先调用它，"
                    + "再基于正文片段给出建议并引用真实来源；不要凭空编造知识条目或处理结论。"
                    + "reviewRequired=true表示知识已过复核期，回答必须提醒先核实当前环境与步骤仍适用，不能当作已验证方案；环境未标注时先确认适用范围。")
    public List<KbBrief> searchKnowledgeBase(
            @ToolParam(description = "检索关键词，例如：打印机、VPN、密码重置、邮箱") String keyword) {

        long startNanos = System.nanoTime();
        ToolCallContext.increment();
        if (keyword == null || keyword.isBlank()) {
            metrics.recordToolCall(TOOL_NAME, IsdMetrics.TOOL_EMPTY, startNanos);
            return List.of();
        }
        try {
            List<KbBrief> rows = kbService.search(keyword, 1, LIMIT)
                    .items()
                    .stream()
                    .map(item -> {
                        var detail = kbService.getItem(item.id());
                        String excerpt = AgentService.relevantSnippet(detail.content(), keyword);
                        KnowledgeReference reference = new KnowledgeReference(item.id(), item.title(), excerpt,
                                detail.environment(), detail.reviewDueAt());
                        ToolCallContext.reference(reference);
                        return new KbBrief(item.id(), item.title(), item.categoryName(), item.tags(), excerpt,
                                reference.environment(), reference.reviewDueAt(), reference.reviewRequired());
                    })
                    .toList();
            metrics.recordToolCall(TOOL_NAME,
                    rows.isEmpty() ? IsdMetrics.TOOL_EMPTY : IsdMetrics.TOOL_SUCCESS, startNanos);
            return rows;
        } catch (RuntimeException ex) {
            // 同上：异常原样抛出，埋点不改变模型看到的结果
            metrics.recordToolCall(TOOL_NAME, IsdMetrics.TOOL_ERROR, startNanos);
            throw ex;
        }
    }
}
