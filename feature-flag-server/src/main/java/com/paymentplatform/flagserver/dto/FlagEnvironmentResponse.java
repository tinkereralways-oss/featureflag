package com.paymentplatform.flagserver.dto;

public record FlagEnvironmentResponse(
        Long id,
        String environment,
        boolean enabled,
        Integer rolloutPercentage
) {
}
