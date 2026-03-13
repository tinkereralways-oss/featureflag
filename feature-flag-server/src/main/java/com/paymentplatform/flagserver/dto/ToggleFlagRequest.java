package com.paymentplatform.flagserver.dto;

import jakarta.validation.constraints.NotNull;

public record ToggleFlagRequest(
        @NotNull Boolean enabled
) {
}
