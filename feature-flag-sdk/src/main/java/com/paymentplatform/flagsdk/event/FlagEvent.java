package com.paymentplatform.flagsdk.event;

import java.time.Instant;

public record FlagEvent(
        String activationId,
        FlagEventType eventType,
        String flagKey,
        String flagId,
        String environment,
        boolean newEnabled,
        Instant timestamp
) {
}
