package com.isd.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 状态变更请求。action 只接受 RESOLVE / CLOSE；
 * CLAIM / ASSIGN 有专用接口（因为还要带 assigneeId 或需要不同的权限校验）。
 */
public record ChangeStatusRequest(

        @NotBlank(message = "action 不能为空")
        String action,

        @Size(max = 500, message = "备注不能超过 500 个字符")
        String comment
) {
}
