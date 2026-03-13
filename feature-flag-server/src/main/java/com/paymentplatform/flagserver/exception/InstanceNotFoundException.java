package com.paymentplatform.flagserver.exception;

public class InstanceNotFoundException extends RuntimeException {

    public InstanceNotFoundException(String instanceId) {
        super("Instance not found: " + instanceId);
    }
}
