package com.isd.kb.dto;

import java.time.LocalDateTime;

import com.isd.kb.entity.KbItem;

public record KbItemResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        String content,
        String tags,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static KbItemResponse from(KbItem item) {
        return new KbItemResponse(item.getId(), item.getCategoryId(), item.getCategoryName(),
                item.getTitle(), item.getContent(), item.getTags(), item.getCreatedBy(),
                item.getCreatedAt(), item.getUpdatedAt());
    }
}
