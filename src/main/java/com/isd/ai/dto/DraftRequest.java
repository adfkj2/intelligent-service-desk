package com.isd.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DraftRequest(
        @NotBlank @Size(max = 2000) String description,
        @Pattern(regexp = "[0-9a-fA-F-]{36}") String conversationId) {
}
