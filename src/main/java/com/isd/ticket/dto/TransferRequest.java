package com.isd.ticket.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransferRequest(@NotNull Long assigneeId, @NotBlank @Size(max = 500) String reason) {
}
