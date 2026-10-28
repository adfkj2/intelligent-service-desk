package com.isd.ai.dto;

import com.isd.kb.dto.KbItemSummary;

/**
 * 给模型看的知识条目摘要（同样不含正文，正文由模型按需再决定要不要看，v1 不提供该工具）。
 */
public record KbBrief(Long id, String title, String categoryName, String tags) {

    public static KbBrief from(KbItemSummary item) {
        return new KbBrief(item.id(), item.title(), item.categoryName(), item.tags());
    }
}
