package com.isd.kb.dto;

import java.time.LocalDateTime;

import com.isd.kb.entity.KbCategory;

public record KbCategoryResponse(Long id, String name, String description, LocalDateTime createdAt) {

    public static KbCategoryResponse from(KbCategory category) {
        return new KbCategoryResponse(category.getId(), category.getName(),
                category.getDescription(), category.getCreatedAt());
    }
}
