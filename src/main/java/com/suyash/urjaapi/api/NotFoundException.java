package com.suyash.urjaapi.api;

/** Thrown when a meter or transformer ID doesn't exist → HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
