package com.paymentplatform.flagserver.exception;

public class InvalidLifecycleTransitionException extends RuntimeException {

    public InvalidLifecycleTransitionException(String message) {
        super(message);
    }
}
