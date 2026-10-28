package com.isd.kb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateItemRequest(

        @NotNull(message = "categoryId 不能为空")
        Long categoryId,

        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题不能超过 128 个字符")
        String title,

        @NotBlank(message = "正文不能为空")
        @Size(max = 20000, message = "正文不能超过 20000 个字符")
        String content,

        @Size(max = 255, message = "标签不能超过 255 个字符")
        String tags
) {
}
