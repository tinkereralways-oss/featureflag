package com.paymentplatform.flagserver.dto;

public record UpdateFlagRequest(
        String name,
        String description,
        String owner,
        Integer staleAfterDays
) {
}
