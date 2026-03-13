package com.paymentplatform.flagserver.dto;

import jakarta.validation.constraints.NotBlank;

public record LifecycleTransitionRequest(
        @NotBlank(message = "Target state is required")
        String targetState,

        String reason,

        @NotBlank(message = "Transitioned by is required")
        String transitionedBy
) {
}
