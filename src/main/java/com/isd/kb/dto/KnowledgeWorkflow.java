package com.isd.kb.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Review-only drafts are separate from the searchable, employee-visible knowledge base. */
public final class KnowledgeWorkflow {
    private KnowledgeWorkflow() { }

    public record FromTicket(@NotNull @Positive Long ticketId, @Size(max = 255) String environment) { }
    public record EditDraft(@NotBlank @Size(max = 128) String title,
                            @NotBlank @Size(max = 20000) String content,
                            @Size(max = 255) String tags, @Positive Long categoryId,
                            @Size(max = 255) String environment, LocalDateTime reviewDueAt,
                            @NotNull Integer version) { }
    public record Publish(@NotNull @Positive Long categoryId, @NotNull Integer version) { }
    public record Review(@NotNull LocalDateTime reviewDueAt, @Size(max = 255) String environment) { }
    public record Feedback(@NotNull Boolean helpful) { }
    public record FeedbackSummary(long helpful, long unhelpful, Boolean myFeedback) { }
    public record Draft(Long id, Long ticketId, Long categoryId, String title, String content,
                        String tags, String environment, LocalDateTime reviewDueAt, String status,
                        Long createdBy, Long reviewedBy, Long publishedItemId, String source,
                        int version, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
