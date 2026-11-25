package com.raster.moneycraft.desktop.device;

/**
 * Exact 7-state output lifecycle for local desktop print attempts, kept strictly separate
 * from financial voucher Idempotency-Key commit state (Doc 28 §6, DEV-002).
 */
public enum PrintAttemptState {
    PREPARED,
    SUBMITTING,
    SUBMITTED,
    CONFIRMED_COMPLETE,
    FAILED_BEFORE_SUBMISSION,
    OUTCOME_UNKNOWN,
    CANCELLED_BEFORE_SUBMISSION
}
