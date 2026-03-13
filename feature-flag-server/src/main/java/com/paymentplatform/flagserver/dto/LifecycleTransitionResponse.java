package com.paymentplatform.flagserver.dto;

import java.time.Instant;

public record LifecycleTransitionResponse(
        String flagId,
        String flagKey,
        String fromState,
        String toState,
        String reason,
        String transitionedBy,
        Instant transitionedAt
) {
}
