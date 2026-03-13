package com.paymentplatform.flagserver.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public record CreateFlagRequest(
        @NotBlank(message = "flagKey must not be blank")
        @Pattern(regexp = "^[a-z0-9-]+$", message = "flagKey must contain only lowercase letters, numbers, and hyphens")
        String flagKey,

        @NotBlank(message = "name must not be blank")
        String name,

        String description,

        String owner,

        Integer staleAfterDays,

        List<String> environments
) {
}
