package com.paymentplatform.flagserver.dto;

import java.time.Instant;
import java.util.List;

public record ActivationResponse(
        String id,
        String flagId,
        String environment,
        boolean newEnabled,
        String status,
        int totalInstances,
        int ackedInstances,
        int timeoutSeconds,
        List<String> ackedInstanceIds,
        Instant createdAt,
        Instant completedAt
) {
}
