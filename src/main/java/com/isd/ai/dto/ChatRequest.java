package com.isd.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(

        @NotBlank(message = "问题不能为空")
        @Size(max = 1000, message = "问题不能超过 1000 个字符")
        String question
) {
}
