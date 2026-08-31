package com.kareem.cortex;

public final class BrainException extends Exception {

    public BrainException(String message) {
        super(message);
    }

    public BrainException(String message, Throwable cause) {
        super(message, cause);
    }
}
