package com.isd.kb.dto;

import java.time.LocalDateTime;

import com.isd.kb.entity.KbItem;

/**
 * 列表项（不返回 content）：知识条目正文可能上万字，列表页把正文全查出来是最常见的性能坑。
 */
public record KbItemSummary(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String tags,
        Long createdBy,
        LocalDateTime updatedAt
) {

    public static KbItemSummary from(KbItem item) {
        return new KbItemSummary(item.getId(), item.getCategoryId(), item.getCategoryName(),
                item.getTitle(), item.getTags(), item.getCreatedBy(), item.getUpdatedAt());
    }
}
