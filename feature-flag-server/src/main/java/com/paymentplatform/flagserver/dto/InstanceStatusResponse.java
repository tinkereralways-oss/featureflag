package com.paymentplatform.flagserver.dto;

import java.time.Instant;

public record InstanceStatusResponse(
        String instanceId,
        String serviceName,
        String hostAddress,
        Integer port,
        String healthStatus,
        String sdkVersion,
        Instant lastHeartbeat,
        Instant registeredAt
) {
}
