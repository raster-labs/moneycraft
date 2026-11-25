package com.raster.moneycraft.desktop.keyboard;

/**
 * Thrown when a modal surface declares colliding, missing, or malformed action-letter mappings
 * (AT10-KEY-001-E, AT09-KEY-002-C).
 */
public final class InvalidModalActionSetException extends RuntimeException {

    public InvalidModalActionSetException(String message) {
        super(message);
    }
}
