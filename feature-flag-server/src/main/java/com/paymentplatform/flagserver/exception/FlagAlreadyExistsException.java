package com.paymentplatform.flagserver.exception;

public class FlagAlreadyExistsException extends RuntimeException {

    public FlagAlreadyExistsException(String flagKey) {
        super("Feature flag already exists with key: " + flagKey);
    }
}
