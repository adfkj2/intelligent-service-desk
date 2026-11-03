package com.isd.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record ChatRequest(

        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题不能超过 1000 个字符")
        String question,
        @Pattern(regexp = "[0-9a-fA-F-]{36}", message = "会话标识不合法") String conversationId,
        @Positive Long ticketId
) {
    public ChatRequest(String question) {
        this(question, null, null);
    }
}
