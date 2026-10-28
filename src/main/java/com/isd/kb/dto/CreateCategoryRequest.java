package com.isd.kb.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(

        @NotBlank(message = "分类名不能为空")
        @Size(max = 64, message = "分类名不能超过 64 个字符")
        String name,

        @Size(max = 255, message = "分类说明不能超过 255 个字符")
        String description
) {
}
