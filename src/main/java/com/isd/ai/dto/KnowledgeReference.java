package com.isd.ai.dto;

import java.time.LocalDateTime;

import com.isd.common.time.BusinessTime;

public record KnowledgeReference(Long id, String title, String excerpt,
                                 String environment, LocalDateTime reviewDueAt, boolean reviewRequired) {
    public KnowledgeReference(Long id, String title, String excerpt) {
        this(id, title, excerpt, null, null, false);
    }

    public KnowledgeReference(Long id, String title, String excerpt, String environment, LocalDateTime reviewDueAt) {
        this(id, title, excerpt, environment, reviewDueAt,
                reviewDueAt != null && !reviewDueAt.isAfter(BusinessTime.now()));
    }
}
