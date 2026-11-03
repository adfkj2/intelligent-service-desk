package com.isd.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddReplyRequest(@NotBlank @Size(max = 5000) String content,
                              @Pattern(regexp = "PUBLIC|INTERNAL") String visibility) {
}
