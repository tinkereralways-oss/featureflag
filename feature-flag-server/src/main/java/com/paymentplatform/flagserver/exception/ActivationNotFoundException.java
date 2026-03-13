package com.paymentplatform.flagserver.exception;

public class ActivationNotFoundException extends RuntimeException {
    public ActivationNotFoundException(String activationId) {
        super("Activation not found: " + activationId);
    }
}
