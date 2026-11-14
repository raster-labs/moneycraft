package com.raster.moneycraft.reader.model;

import java.util.Objects;

/**
 * Deterministic diagnostic emitted during binary sector inspection, shadow verification, or reconciliation.
 */
public record ReaderDiagnostic(
        DiagnosticSeverity severity,
        String code,
        String storeName,
        String message
) {
    public ReaderDiagnostic {
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(storeName, "storeName must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }
}
