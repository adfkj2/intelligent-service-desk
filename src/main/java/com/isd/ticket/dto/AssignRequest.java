package com.isd.ticket.dto;

import jakarta.validation.constraints.NotNull;

public record AssignRequest(

        @NotNull(message = "assigneeId 不能为空")
        Long assigneeId
) {
}
