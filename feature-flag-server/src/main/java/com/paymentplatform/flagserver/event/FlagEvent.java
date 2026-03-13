package com.paymentplatform.flagserver.event;

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
    public static FlagEvent prepare(String activationId, String flagKey, String flagId,
                                     String environment, boolean newEnabled) {
        return new FlagEvent(activationId, FlagEventType.FLAG_PREPARE, flagKey, flagId,
                environment, newEnabled, Instant.now());
    }

    public static FlagEvent commit(String activationId, String flagKey, String flagId,
                                    String environment, boolean newEnabled) {
        return new FlagEvent(activationId, FlagEventType.FLAG_COMMIT, flagKey, flagId,
                environment, newEnabled, Instant.now());
    }

    public static FlagEvent rollback(String activationId, String flagKey, String flagId,
                                      String environment, boolean newEnabled) {
        return new FlagEvent(activationId, FlagEventType.FLAG_ROLLBACK, flagKey, flagId,
                environment, newEnabled, Instant.now());
    }
}
