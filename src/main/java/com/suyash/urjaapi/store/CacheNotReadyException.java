package com.suyash.urjaapi.store;

/** The first download from the portal hasn't finished (or has failed), so there's no data to serve yet. */
public class CacheNotReadyException extends RuntimeException {

    public CacheNotReadyException(String message) {
        super(message);
    }
}
