package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.VoucherDtos;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional gap-free voucher number allocator and GSTR-1 Table 13 cancelled/unused tracker.
 * Acquires mc_number_series FOR UPDATE strictly at its MCJ1-TXN-01 lock slot.
 */
@Component
public class VoucherNumberingService {

    private static final UUID ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final JdbcTemplate businessJdbcTemplate;

    public VoucherNumberingService(
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate
    ) {
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public AllocatedVoucherNumber allocateNumber(
        UUID companyId,
        UUID journalId,
        UUID voucherTypeId,
        String numberingMethod,
        boolean preventDuplicateNumbers,
        UUID fiscalYearId,
        UUID gstRegistrationId,
        String requestedManualNumber
    ) {
        String trimmedManual = requestedManualNumber == null ? null : requestedManualNumber.trim();
        if (trimmedManual != null && trimmedManual.isEmpty()) {
            trimmedManual = null;
        }
        if (trimmedManual != null && trimmedManual.length() > 32) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-VOUCHER-NUMBER-TOO-LONG",
                "Voucher Number Too Long",
                "Voucher number must not exceed 32 characters."
            );
        }

        if ("NONE".equals(numberingMethod)) {
            return new AllocatedVoucherNumber(null, trimmedManual, null);
        }

        if ("MANUAL".equals(numberingMethod)) {
            if (trimmedManual == null) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-VOUCHER-NUMBER-REQUIRED",
                    "Manual Voucher Number Required",
                    "Voucher type uses MANUAL numbering; provide a non-blank 'voucherNumber'."
                );
            }
            if (preventDuplicateNumbers) {
                assertUniqueVoucherNumber(companyId, voucherTypeId, fiscalYearId, trimmedManual, journalId);
            }
            return new AllocatedVoucherNumber(null, trimmedManual, null);
        }

        LockedSeriesRow series = ensureAndLockDefaultSeries(
            companyId,
            voucherTypeId,
            fiscalYearId,
            gstRegistrationId
        );

        if ("AUTOMATIC_RETAIN_NUMBERS".equals(numberingMethod)) {
            List<AvailableUnusedRow> unusedRows = businessJdbcTemplate.query(
                """
                SELECT id, voucher_number, numeric_seq
                  FROM mc_unused_voucher_number
                 WHERE company_id = ?
                   AND number_series_id = ?
                   AND status = 'AVAILABLE'
                 ORDER BY numeric_seq ASC NULLS LAST, released_at ASC, id ASC
                 LIMIT 1
                   FOR UPDATE
                """,
                (rs, rowNum) -> new AvailableUnusedRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("voucher_number"),
                    rs.getObject("numeric_seq", Long.class)
                ),
                companyId,
                series.id()
            );
            if (!unusedRows.isEmpty()) {
                AvailableUnusedRow reused = unusedRows.getFirst();
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_unused_voucher_number
                       SET status = 'REUSED',
                           reused_by_journal_id = ?
                     WHERE company_id = ? AND id = ?
                    """,
                    journalId,
                    companyId,
                    reused.id()
                );
                return new AllocatedVoucherNumber(series.id(), reused.voucherNumber(), reused.numericSeq());
            }
        }

        long seqNo = series.nextNumber();
        String formatted = formatSeriesNumber(
            series.prefix(),
            seqNo,
            series.minWidth(),
            series.prefillWithZero(),
            series.suffix()
        );

        while (preventDuplicateNumbers
            && isVoucherNumberInUse(companyId, voucherTypeId, fiscalYearId, formatted, journalId)) {
            seqNo++;
            formatted = formatSeriesNumber(
                series.prefix(),
                seqNo,
                series.minWidth(),
                series.prefillWithZero(),
                series.suffix()
            );
        }

        businessJdbcTemplate.update(
            """
            UPDATE mc_number_series
               SET next_number = ?,
                   version_no = version_no + 1,
                   updated_at = clock_timestamp()
             WHERE company_id = ? AND id = ?
            """,
            seqNo + 1L,
            companyId,
            series.id()
        );

        return new AllocatedVoucherNumber(series.id(), formatted, seqNo);
    }

    @Transactional(transactionManager = "transactionManager", propagation = Propagation.MANDATORY)
    public void recordCancelledForGstr1(
        UUID companyId,
        UUID numberSeriesId,
        UUID voucherTypeId,
        UUID journalId,
        String voucherNumber,
        Long voucherSeqNo,
        String reason
    ) {
        if (numberSeriesId == null || voucherNumber == null || voucherNumber.isBlank()) {
            return;
        }
        businessJdbcTemplate.query(
            "SELECT id FROM mc_number_series WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            numberSeriesId
        );
        String cleanReason = reason == null || reason.isBlank()
            ? "Cancelled via Alt+X"
            : (reason.trim().length() <= 120 ? reason.trim() : reason.trim().substring(0, 120));
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_unused_voucher_number (
                id, company_id, number_series_id, voucher_type_id,
                voucher_number, numeric_seq, released_from_journal_id,
                released_reason, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CANCELLED_FOR_GSTR1')
            """,
            UUID.randomUUID(),
            companyId,
            numberSeriesId,
            voucherTypeId,
            voucherNumber,
            voucherSeqNo,
            journalId,
            cleanReason
        );
    }

    public VoucherDtos.NextVoucherNumberResponse previewNextNumber(
        UUID companyId,
        UUID voucherTypeId,
        String voucherTypeName,
        String numberingMethod,
        LocalDate voucherDate
    ) {
        if ("NONE".equals(numberingMethod)) {
            return new VoucherDtos.NextVoucherNumberResponse(
                voucherTypeId,
                voucherTypeName,
                numberingMethod,
                null,
                null,
                null,
                false,
                false
            );
        }
        if ("MANUAL".equals(numberingMethod)) {
            return new VoucherDtos.NextVoucherNumberResponse(
                voucherTypeId,
                voucherTypeName,
                numberingMethod,
                null,
                null,
                null,
                true,
                false
            );
        }

        LocalDate targetDate = voucherDate == null ? LocalDate.now() : voucherDate;
        UUID fiscalYearId = findFiscalYearIdForDate(companyId, targetDate);

        List<LockedSeriesRow> existingSeries = businessJdbcTemplate.query(
            """
            SELECT id, prefix, suffix, starting_number, next_number, min_width, prefill_with_zero
              FROM mc_number_series
             WHERE company_id = ?
               AND voucher_type_id = ?
               AND COALESCE(fiscal_year_id, ?::uuid) = COALESCE(?::uuid, ?::uuid)
               AND COALESCE(gst_registration_id, ?::uuid) = ?::uuid
               AND lower(series_name) = 'default'
            """,
            (rs, rowNum) -> new LockedSeriesRow(
                rs.getObject("id", UUID.class),
                rs.getString("prefix"),
                rs.getString("suffix"),
                rs.getLong("starting_number"),
                rs.getLong("next_number"),
                rs.getShort("min_width"),
                rs.getBoolean("prefill_with_zero")
            ),
            companyId,
            voucherTypeId,
            ZERO_UUID,
            fiscalYearId,
            ZERO_UUID,
            ZERO_UUID,
            ZERO_UUID
        );

        if (existingSeries.isEmpty()) {
            return new VoucherDtos.NextVoucherNumberResponse(
                voucherTypeId,
                voucherTypeName,
                numberingMethod,
                "1",
                "1",
                1L,
                false,
                true
            );
        }

        LockedSeriesRow series = existingSeries.getFirst();
        if ("AUTOMATIC_RETAIN_NUMBERS".equals(numberingMethod)) {
            List<AvailableUnusedRow> unused = businessJdbcTemplate.query(
                """
                SELECT id, voucher_number, numeric_seq
                  FROM mc_unused_voucher_number
                 WHERE company_id = ?
                   AND number_series_id = ?
                   AND status = 'AVAILABLE'
                 ORDER BY numeric_seq ASC NULLS LAST, released_at ASC, id ASC
                 LIMIT 1
                """,
                (rs, rowNum) -> new AvailableUnusedRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("voucher_number"),
                    rs.getObject("numeric_seq", Long.class)
                ),
                companyId,
                series.id()
            );
            if (!unused.isEmpty()) {
                AvailableUnusedRow first = unused.getFirst();
                return new VoucherDtos.NextVoucherNumberResponse(
                    voucherTypeId,
                    voucherTypeName,
                    numberingMethod,
                    first.voucherNumber(),
                    first.voucherNumber(),
                    first.numericSeq(),
                    false,
                    true
                );
            }
        }

        long seqNo = series.nextNumber();
        String formatted = formatSeriesNumber(
            series.prefix(),
            seqNo,
            series.minWidth(),
            series.prefillWithZero(),
            series.suffix()
        );
        return new VoucherDtos.NextVoucherNumberResponse(
            voucherTypeId,
            voucherTypeName,
            numberingMethod,
            formatted,
            formatted,
            seqNo,
            false,
            true
        );
    }

    public void assertUniqueManualNumberOnAlter(
        UUID companyId,
        UUID voucherTypeId,
        UUID fiscalYearId,
        String voucherNumber,
        UUID excludeJournalId
    ) {
        assertUniqueVoucherNumber(companyId, voucherTypeId, fiscalYearId, voucherNumber, excludeJournalId);
    }

    private LockedSeriesRow ensureAndLockDefaultSeries(
        UUID companyId,
        UUID voucherTypeId,
        UUID fiscalYearId,
        UUID gstRegistrationId
    ) {
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_number_series (
                id, company_id, voucher_type_id, fiscal_year_id, gst_registration_id,
                series_name, prefix, suffix, starting_number, next_number, min_width,
                prefill_with_zero, restart_periodicity
            ) VALUES (?, ?, ?, ?, ?, 'Default', '', '', 1, 1, 1, false, 'YEARLY')
            ON CONFLICT (
                company_id,
                voucher_type_id,
                COALESCE(fiscal_year_id, '00000000-0000-0000-0000-000000000000'::uuid),
                COALESCE(gst_registration_id, '00000000-0000-0000-0000-000000000000'::uuid),
                lower(series_name)
            ) DO NOTHING
            """,
            UUID.randomUUID(),
            companyId,
            voucherTypeId,
            fiscalYearId,
            gstRegistrationId
        );

        List<LockedSeriesRow> locked = businessJdbcTemplate.query(
            """
            SELECT id, prefix, suffix, starting_number, next_number, min_width, prefill_with_zero
              FROM mc_number_series
             WHERE company_id = ?
               AND voucher_type_id = ?
               AND COALESCE(fiscal_year_id, ?::uuid) = COALESCE(?::uuid, ?::uuid)
               AND COALESCE(gst_registration_id, ?::uuid) = COALESCE(?::uuid, ?::uuid)
               AND lower(series_name) = 'default'
               FOR UPDATE
            """,
            (rs, rowNum) -> new LockedSeriesRow(
                rs.getObject("id", UUID.class),
                rs.getString("prefix"),
                rs.getString("suffix"),
                rs.getLong("starting_number"),
                rs.getLong("next_number"),
                rs.getShort("min_width"),
                rs.getBoolean("prefill_with_zero")
            ),
            companyId,
            voucherTypeId,
            ZERO_UUID,
            fiscalYearId,
            ZERO_UUID,
            ZERO_UUID,
            gstRegistrationId,
            ZERO_UUID
        );
        if (locked.isEmpty()) {
            throw new IllegalStateException("Failed to lock mc_number_series for voucherType=" + voucherTypeId);
        }
        return locked.getFirst();
    }

    private UUID findFiscalYearIdForDate(UUID companyId, LocalDate date) {
        List<UUID> rows = businessJdbcTemplate.query(
            """
            SELECT id
              FROM mc_fiscal_year
             WHERE company_id = ?
               AND start_date <= ?
               AND end_date >= ?
             LIMIT 1
            """,
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            date,
            date
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void assertUniqueVoucherNumber(
        UUID companyId,
        UUID voucherTypeId,
        UUID fiscalYearId,
        String voucherNumber,
        UUID excludeJournalId
    ) {
        if (isVoucherNumberInUse(companyId, voucherTypeId, fiscalYearId, voucherNumber, excludeJournalId)) {
            throw new MoneyCraftException(
                HttpStatus.CONFLICT,
                "MC-ACC-VOUCHER-NUMBER-DUPLICATE",
                "Duplicate Voucher Number",
                "Voucher number '" + voucherNumber + "' already exists for this voucher type and fiscal year."
            );
        }
    }

    private boolean isVoucherNumberInUse(
        UUID companyId,
        UUID voucherTypeId,
        UUID fiscalYearId,
        String voucherNumber,
        UUID excludeJournalId
    ) {
        Integer cnt = businessJdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM mc_journal
             WHERE company_id = ?
               AND voucher_type_id = ?
               AND COALESCE(fiscal_year_id, ?::uuid) = COALESCE(?::uuid, ?::uuid)
               AND voucher_number = ?
               AND (?::uuid IS NULL OR id <> ?::uuid)
            """,
            Integer.class,
            companyId,
            voucherTypeId,
            ZERO_UUID,
            fiscalYearId,
            ZERO_UUID,
            voucherNumber,
            excludeJournalId,
            excludeJournalId
        );
        return cnt != null && cnt > 0;
    }

    public static String formatSeriesNumber(
        String prefix,
        long seqNo,
        int minWidth,
        boolean prefillWithZero,
        String suffix
    ) {
        String digits = Long.toString(seqNo);
        if (prefillWithZero && minWidth > digits.length()) {
            digits = "0".repeat(minWidth - digits.length()) + digits;
        }
        String pfx = prefix == null ? "" : prefix;
        String sfx = suffix == null ? "" : suffix;
        return pfx + digits + sfx;
    }

    public record AllocatedVoucherNumber(
        UUID numberSeriesId,
        String voucherNumber,
        Long voucherSeqNo
    ) {
    }

    private record LockedSeriesRow(
        UUID id,
        String prefix,
        String suffix,
        long startingNumber,
        long nextNumber,
        int minWidth,
        boolean prefillWithZero
    ) {
    }

    private record AvailableUnusedRow(
        UUID id,
        String voucherNumber,
        Long numericSeq
    ) {
    }
}
