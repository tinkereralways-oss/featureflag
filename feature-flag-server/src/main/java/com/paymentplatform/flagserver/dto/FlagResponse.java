package com.paymentplatform.flagserver.dto;

import java.time.Instant;
import java.util.List;

public record FlagResponse(
        String id,
        String flagKey,
        String name,
        String description,
        String owner,
        String lifecycleState,
        Integer staleAfterDays,
        List<FlagEnvironmentResponse> environments,
        Instant createdAt,
        Instant updatedAt
) {
}
