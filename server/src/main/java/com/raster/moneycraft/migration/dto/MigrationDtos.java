package com.raster.moneycraft.migration.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Clean-room TallyPrime .1800 native staging, reconciliation proof, and promotion wire contracts.
 * All monetary amounts and variances are serialized as exact fixed-point strings.
 */
public final class MigrationDtos {

    private MigrationDtos() {
    }

    public record StageTally1800Request(
        String snapshotDirectoryPath,
        String companionXmlDirectoryPath
    ) {
    }

    public record ImportJobSummaryDto(
        UUID id,
        UUID jobId,
        String jobType,
        String status,
        String sourceCompanyName,
        String snapshotDirectoryPath,
        boolean isZeroVariance,
        String varianceAmount,
        String proofSha256Hex,
        Instant createdAt,
        Instant committedAt
    ) {
    }

    public record ImportJobProofDto(
        UUID id,
        UUID jobId,
        String status,
        String sourceCompanyName,
        String sourceCompanyNumber,
        String snapshotSourceType,
        String snapshotDirectoryPath,
        String companionXmlDirectoryPath,
        boolean sector0Verified,
        boolean cmpSaveParityVerified,
        int sourceGroupCount,
        int stagedGroupCount,
        int sourceLedgerCount,
        int stagedLedgerCount,
        int sourceStockItemCount,
        int stagedStockItemCount,
        int sourceVoucherCount,
        int stagedVoucherCount,
        String sourceGrossDebit,
        String stagedGrossDebit,
        String sourceGrossCredit,
        String stagedGrossCredit,
        String sourceNetTrialBalance,
        String stagedNetTrialBalance,
        String sourceClosingStock,
        String stagedClosingStock,
        String sourceNetProfit,
        String stagedNetProfit,
        String varianceDebit,
        String varianceCredit,
        String varianceAmount,
        boolean isZeroVariance,
        String proofSha256Hex,
        int editLogBaselineVersion,
        List<String> warnings,
        Instant stagedAt,
        Instant committedAt
    ) {
    }
}
