package com.paymentplatform.flagserver.exception;

public class ActivationConflictException extends RuntimeException {
    public ActivationConflictException(String message) {
        super(message);
    }
}
