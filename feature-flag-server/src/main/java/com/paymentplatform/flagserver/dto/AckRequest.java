package com.paymentplatform.flagserver.dto;

import jakarta.validation.constraints.NotBlank;

public record AckRequest(
        @NotBlank String instanceId
) {
}
