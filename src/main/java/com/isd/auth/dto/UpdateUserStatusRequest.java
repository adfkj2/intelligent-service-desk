package com.isd.auth.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateUserStatusRequest(

        @NotNull(message = "status 不能为空")
        Integer status
) {
}
