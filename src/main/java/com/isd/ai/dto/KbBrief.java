package com.isd.ai.dto;

import com.isd.kb.dto.KbItemSummary;
import java.time.LocalDateTime;

/**
 * 给模型看的知识条目摘要与相关正文片段。
 */
public record KbBrief(Long id, String title, String categoryName, String tags, String excerpt,
                      String environment, LocalDateTime reviewDueAt, boolean reviewRequired) {

    public KbBrief(Long id, String title, String categoryName, String tags) {
        this(id, title, categoryName, tags, "", null, null, false);
    }

    public KbBrief(Long id, String title, String categoryName, String tags, String excerpt) {
        this(id, title, categoryName, tags, excerpt, null, null, false);
    }

    public static KbBrief from(KbItemSummary item) {
        return new KbBrief(item.id(), item.title(), item.categoryName(), item.tags());
    }
}
