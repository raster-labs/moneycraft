package com.raster.moneycraft.reader.api;

/**
 * Fail-closed exception thrown when a .1800 binary snapshot or companion XML fails sector, shadow, or accounting verification.
 */
public final class CorruptSnapshotException extends RuntimeException {

    private final String errorCode;

    public CorruptSnapshotException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CorruptSnapshotException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
