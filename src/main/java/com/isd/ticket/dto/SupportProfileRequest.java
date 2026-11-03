package com.isd.ticket.dto;

import java.util.List;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SupportProfileRequest(@NotNull @Size(max = 4) List<String> skills) {
}
