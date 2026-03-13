package com.paymentplatform.flagserver.dto;

import jakarta.validation.constraints.NotBlank;

public record HeartbeatRequest(
        @NotBlank(message = "instanceId must not be blank")
        String instanceId
) {
}
