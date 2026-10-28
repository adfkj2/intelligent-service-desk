package com.isd.ai.tools;

import java.util.List;

import com.isd.ai.dto.KbBrief;
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

    private final KbService kbService;

    public KnowledgeTools(KbService kbService) {
        this.kbService = kbService;
    }

    @Tool(name = "search_knowledge_base",
            description = "按关键词检索企业 IT 知识库，返回最相关条目的 id、标题、所属分类和标签。"
                    + "当用户描述具体故障现象（如打印机离线、VPN 连不上、密码重置）时先调用它，"
                    + "再基于返回的标题给出处理建议；不要凭空编造知识条目。")
    public List<KbBrief> searchKnowledgeBase(
            @ToolParam(description = "检索关键词，例如：打印机、VPN、密码重置、邮箱") String keyword) {

        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return kbService.search(keyword, 1, LIMIT)
                .items()
                .stream()
                .map(KbBrief::from)
                .toList();
    }
}
