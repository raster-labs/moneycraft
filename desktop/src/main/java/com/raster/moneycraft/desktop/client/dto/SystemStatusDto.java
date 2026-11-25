package com.raster.moneycraft.desktop.client.dto;

public record SystemStatusDto(
    String securityState,
    long fenceEpoch,
    String version,
    String quarantineReason
) {
}
