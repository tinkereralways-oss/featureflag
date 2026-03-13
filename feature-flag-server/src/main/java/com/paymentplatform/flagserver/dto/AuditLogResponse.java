package com.paymentplatform.flagserver.dto;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        String flagId,
        String action,
        String oldValue,
        String newValue,
        String changedBy,
        String metadata,
        Instant createdAt
) {
}
