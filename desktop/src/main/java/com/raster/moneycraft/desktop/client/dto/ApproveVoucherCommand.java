package com.raster.moneycraft.desktop.client.dto;

public record ApproveVoucherCommand(
    Boolean postImmediately,
    String remarks
) {
}
