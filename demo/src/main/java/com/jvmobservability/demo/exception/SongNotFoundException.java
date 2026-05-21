package com.jvmobservability.demo.exception;

/**
 * Thrown when a requested song ID does not exist in the store.
 * Mapped to HTTP 404 by the global exception handler.
 */
public class SongNotFoundException extends RuntimeException {

    public SongNotFoundException(String id) {
        super("Song not found: " + id);
    }
}
