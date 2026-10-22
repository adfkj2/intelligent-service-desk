package com.isd.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建工单的请求体。
 * 用 record 表达"不可变的输入契约"：字段一旦绑定就不再变化，避免 Service 层被意外改写。
 */
public record CreateTicketRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 128, message = "标题不能超过 128 个字符")
        String title,

        @Size(max = 2000, message = "描述不能超过 2000 个字符")
        String description,

        @NotBlank(message = "分类不能为空")
        @Pattern(regexp = "IT_FAULT|PERMISSION|DEVICE|OTHER",
                message = "分类只能是 IT_FAULT、PERMISSION、DEVICE、OTHER 之一")
        String category,

        @Pattern(regexp = "LOW|MEDIUM|HIGH|URGENT",
                message = "优先级只能是 LOW、MEDIUM、HIGH、URGENT 之一")
        String priority
) {
}
