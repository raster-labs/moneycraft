package com.raster.moneycraft.desktop.device;

/**
 * Distinguishes transport/spooler byte acceptance from printer-confirmed physical completion
 * and deliberate operator queue reconciliation (Doc 28 §6, ND-S06, DEV-002).
 */
public enum CompletionEvidenceSource {
    NONE,
    SPOOLER_DATA_TRANSFERRED,
    TRANSPORT_BYTES_ACCEPTED,
    PRINTER_HARDWARE_CONFIRMED,
    OPERATOR_RECONCILED_PRINTED,
    OPERATOR_RECONCILED_NOT_PRINTED
}
