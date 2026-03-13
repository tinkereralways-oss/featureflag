package com.paymentplatform.flagserver.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterInstanceRequest(
        @NotBlank(message = "instanceId must not be blank")
        String instanceId,

        @NotBlank(message = "serviceName must not be blank")
        String serviceName,

        String hostAddress,

        Integer port,

        String sdkVersion
) {
}
