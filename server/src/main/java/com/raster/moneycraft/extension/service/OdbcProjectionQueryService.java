package com.raster.moneycraft.extension.service;

import com.raster.moneycraft.accounting.service.CompanyMasterSequenceHelper;
import com.raster.moneycraft.extension.dto.ExtensionDtos;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import com.raster.moneycraft.reporting.service.FinancialStatementService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Service-backed read-only inbound projection query adapter (EXT-008).
 * Exposes authorised tabular projections over Trial Balance, Ledgers, and Vouchers
 * with zero raw SQL execution, zero PostgreSQL credential exposure, and strict redaction.
 */
@Service
public class OdbcProjectionQueryService {

    private final ExtensionAccessHelper accessHelper;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final FinancialStatementService financialStatementService;
    private final JdbcTemplate businessJdbcTemplate;

    public OdbcProjectionQueryService(
        ExtensionAccessHelper accessHelper,
        CompanyMasterSequenceHelper sequenceHelper,
        FinancialStatementService financialStatementService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate
    ) {
        this.accessHelper = accessHelper;
        this.sequenceHelper = sequenceHelper;
        this.financialStatementService = financialStatementService;
        this.businessJdbcTemplate = businessJdbcTemplate;
    }

    public ExtensionDtos.OdbcProjectionQueryResponse executeQuery(
        MoneyCraftPrincipal principal,
        UUID companyId,
        ExtensionDtos.OdbcProjectionQueryRequest request
    ) {
        accessHelper.requireCompanyRead(principal, companyId);
        if (request == null || request.projection() == null || request.projection().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-EXT-ODBC-PROJECTION-REQUIRED",
                "Projection Name Required",
                "Specify projection in TRIAL_BALANCE, LEDGERS, or VOUCHERS."
            );
        }
        if (request.rawSql() != null && !request.rawSql().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-ODBC-RAW-SQL-FORBIDDEN",
                "Raw SQL Execution Forbidden",
                "Service-backed ODBC queries accept closed projection names only; raw SQL and mutations are prohibited."
            );
        }

        int maxRows = CompanyMasterSequenceHelper.normalizePageLimit(request.limit(), 200, 1000);
        int scale = sequenceHelper.readCompanyCurrencyMeta(companyId).currencyScale();
        String normProj = normalizeProjectionName(request.projection());
        Instant asOf = Instant.now();

        return switch (normProj) {
            case "TRIAL_BALANCE" -> queryTrialBalanceProjection(
                principal,
                companyId,
                request.fromDate(),
                request.toDate(),
                Boolean.TRUE.equals(request.includeOptional()),
                maxRows,
                asOf
            );
            case "LEDGERS" -> queryLedgersProjection(companyId, scale, maxRows, asOf);
            case "VOUCHERS" -> queryVouchersProjection(
                companyId,
                scale,
                request.fromDate(),
                request.toDate(),
                Boolean.TRUE.equals(request.includeOptional()),
                maxRows,
                asOf
            );
            case "STOCK_SUMMARY" -> queryStockSummaryProjection(companyId, scale, maxRows, asOf);
            default -> throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-EXT-ODBC-PROJECTION-UNSUPPORTED",
                "Unsupported Inbound ODBC Projection",
                "Admitted read-only projections are TRIAL_BALANCE, LEDGERS, VOUCHERS, and STOCK_SUMMARY."
            );
        };
    }

    private ExtensionDtos.OdbcProjectionQueryResponse queryTrialBalanceProjection(
        MoneyCraftPrincipal principal,
        UUID companyId,
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional,
        int maxRows,
        Instant asOf
    ) {
        ReportingDtos.TrialBalanceReportDto tb = financialStatementService.getTrialBalance(
            principal,
            companyId,
            fromDate,
            toDate,
            includeOptional
        );
        List<ExtensionDtos.OdbcProjectionColumnDto> cols = List.of(
            new ExtensionDtos.OdbcProjectionColumnDto("ledgerId", "UUID"),
            new ExtensionDtos.OdbcProjectionColumnDto("ledgerName", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("groupName", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("nature", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("openingDr", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("openingCr", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("periodDr", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("periodCr", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("closingDr", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("closingCr", "EXACT_DECIMAL")
        );
        List<Map<String, String>> rows = new ArrayList<>();
        for (ReportingDtos.TrialBalanceRowDto r : tb.ledgerRows()) {
            if (rows.size() >= maxRows) {
                break;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("ledgerId", r.ledgerId() == null ? "" : r.ledgerId().toString());
            m.put("ledgerName", nullToEmpty(r.ledgerName()));
            m.put("groupName", nullToEmpty(r.groupName()));
            m.put("nature", nullToEmpty(r.nature()));
            m.put("openingDr", nullToEmpty(r.openingDebit()));
            m.put("openingCr", nullToEmpty(r.openingCredit()));
            m.put("periodDr", nullToEmpty(r.periodDebit()));
            m.put("periodCr", nullToEmpty(r.periodCredit()));
            m.put("closingDr", nullToEmpty(r.closingDebit()));
            m.put("closingCr", nullToEmpty(r.closingCredit()));
            rows.add(m);
        }
        return new ExtensionDtos.OdbcProjectionQueryResponse("TRIAL_BALANCE", asOf, rows.size(), cols, rows);
    }

    private ExtensionDtos.OdbcProjectionQueryResponse queryLedgersProjection(
        UUID companyId,
        int scale,
        int maxRows,
        Instant asOf
    ) {
        List<ExtensionDtos.OdbcProjectionColumnDto> cols = List.of(
            new ExtensionDtos.OdbcProjectionColumnDto("id", "UUID"),
            new ExtensionDtos.OdbcProjectionColumnDto("name", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("aliasCode", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("groupName", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("openingBalance", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("openingSide", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("gstin", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("active", "BOOLEAN")
        );
        List<Map<String, String>> rows = businessJdbcTemplate.query(
            """
            SELECT l.id, l.name, l.alias_code, g.name AS group_name,
                   l.opening_balance, trim(l.opening_side) AS opening_side,
                   l.gstin, l.active
              FROM mc_ledger l
              LEFT JOIN mc_account_group g
                ON g.company_id = l.company_id AND g.id = l.group_id
             WHERE l.company_id = ?
             ORDER BY lower(l.name) ASC, l.id ASC
             LIMIT ?
            """,
            (rs, rowNum) -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id", UUID.class).toString());
                m.put("name", nullToEmpty(rs.getString("name")));
                m.put("aliasCode", nullToEmpty(rs.getString("alias_code")));
                m.put("groupName", nullToEmpty(rs.getString("group_name")));
                m.put("openingBalance", ExactMoneyValidator.formatExactScale(rs.getBigDecimal("opening_balance"), scale));
                m.put("openingSide", nullToEmpty(rs.getString("opening_side")));
                m.put("gstin", nullToEmpty(rs.getString("gstin")));
                m.put("active", Boolean.toString(rs.getBoolean("active")));
                return m;
            },
            companyId,
            maxRows
        );
        return new ExtensionDtos.OdbcProjectionQueryResponse("LEDGERS", asOf, rows.size(), cols, rows);
    }

    private ExtensionDtos.OdbcProjectionQueryResponse queryVouchersProjection(
        UUID companyId,
        int scale,
        LocalDate fromDate,
        LocalDate toDate,
        boolean includeOptional,
        int maxRows,
        Instant asOf
    ) {
        List<ExtensionDtos.OdbcProjectionColumnDto> cols = List.of(
            new ExtensionDtos.OdbcProjectionColumnDto("id", "UUID"),
            new ExtensionDtos.OdbcProjectionColumnDto("voucherDate", "DATE"),
            new ExtensionDtos.OdbcProjectionColumnDto("voucherType", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("voucherNumber", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("status", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("postingNature", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("totalDebit", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("totalCredit", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("narration", "TEXT")
        );

        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder where = new StringBuilder("j.company_id = ? AND j.status = 'POSTED'");
        if (!includeOptional) {
            where.append(" AND j.posting_nature = 'NORMAL'");
        }
        if (fromDate != null) {
            where.append(" AND j.voucher_date >= ?");
            params.add(fromDate);
        }
        if (toDate != null) {
            where.append(" AND j.voucher_date <= ?");
            params.add(toDate);
        }
        params.add(maxRows);

        List<Map<String, String>> rows = businessJdbcTemplate.query(
            """
            SELECT j.id, j.voucher_date, vt.name AS voucher_type_name, j.voucher_number,
                   j.status, j.posting_nature, j.total_debit, j.total_credit, j.narration
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id AND vt.id = j.voucher_type_id
             WHERE """ + where + " ORDER BY j.voucher_date ASC, j.master_seq_id ASC LIMIT ?",
            (rs, rowNum) -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id", UUID.class).toString());
                m.put("voucherDate", rs.getObject("voucher_date", LocalDate.class).toString());
                m.put("voucherType", nullToEmpty(rs.getString("voucher_type_name")));
                m.put("voucherNumber", nullToEmpty(rs.getString("voucher_number")));
                m.put("status", nullToEmpty(rs.getString("status")));
                m.put("postingNature", nullToEmpty(rs.getString("posting_nature")));
                m.put("totalDebit", ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_debit"), scale));
                m.put("totalCredit", ExactMoneyValidator.formatExactScale(rs.getBigDecimal("total_credit"), scale));
                m.put("narration", nullToEmpty(rs.getString("narration")));
                return m;
            },
            params.toArray()
        );
        return new ExtensionDtos.OdbcProjectionQueryResponse("VOUCHERS", asOf, rows.size(), cols, rows);
    }

    private ExtensionDtos.OdbcProjectionQueryResponse queryStockSummaryProjection(
        UUID companyId,
        int scale,
        int maxRows,
        Instant asOf
    ) {
        List<ExtensionDtos.OdbcProjectionColumnDto> cols = List.of(
            new ExtensionDtos.OdbcProjectionColumnDto("id", "UUID"),
            new ExtensionDtos.OdbcProjectionColumnDto("itemName", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("stockGroupName", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("baseUom", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("costingMethod", "TEXT"),
            new ExtensionDtos.OdbcProjectionColumnDto("openingQty", "EXACT_DECIMAL"),
            new ExtensionDtos.OdbcProjectionColumnDto("openingValue", "EXACT_DECIMAL")
        );
        List<Map<String, String>> rows = businessJdbcTemplate.query(
            """
            SELECT si.id, si.name, sg.name AS stock_group_name, u.symbol AS base_uom,
                   si.costing_method, si.opening_qty, si.opening_value, u.decimal_places
              FROM mc_stock_item si
              JOIN mc_uom u ON u.company_id = si.company_id AND u.id = si.base_uom_id
              LEFT JOIN mc_stock_group sg ON sg.company_id = si.company_id AND sg.id = si.stock_group_id
             WHERE si.company_id = ? AND si.active = true
             ORDER BY lower(si.name) ASC, si.id ASC
             LIMIT ?
            """,
            (rs, rowNum) -> {
                int uomScale = Math.max(0, Math.min(6, rs.getInt("decimal_places")));
                Map<String, String> m = new LinkedHashMap<>();
                m.put("id", rs.getObject("id", UUID.class).toString());
                m.put("itemName", nullToEmpty(rs.getString("name")));
                m.put("stockGroupName", nullToEmpty(rs.getString("stock_group_name")));
                m.put("baseUom", nullToEmpty(rs.getString("base_uom")));
                m.put("costingMethod", nullToEmpty(rs.getString("costing_method")));
                m.put("openingQty", ExactMoneyValidator.formatExactScale(rs.getBigDecimal("opening_qty"), uomScale));
                m.put("openingValue", ExactMoneyValidator.formatExactScale(rs.getBigDecimal("opening_value"), scale));
                return m;
            },
            companyId,
            maxRows
        );
        return new ExtensionDtos.OdbcProjectionQueryResponse("STOCK_SUMMARY", asOf, rows.size(), cols, rows);
    }

    private static String normalizeProjectionName(String raw) {
        String u = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("TRIALBALANCE".equals(u) || "TRIAL_BALANCE".equals(u)) {
            return "TRIAL_BALANCE";
        }
        if ("LEDGER".equals(u) || "LEDGERS".equals(u)) {
            return "LEDGERS";
        }
        if ("VOUCHER".equals(u) || "VOUCHERS".equals(u) || "VOUCHER_REGISTER".equals(u)) {
            return "VOUCHERS";
        }
        if ("STOCKSUMMARY".equals(u) || "STOCK_SUMMARY".equals(u)) {
            return "STOCK_SUMMARY";
        }
        return u;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
