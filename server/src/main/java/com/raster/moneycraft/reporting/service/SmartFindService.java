package com.raster.moneycraft.reporting.service;

import com.raster.moneycraft.foundation.company.CompanyDtos;
import com.raster.moneycraft.foundation.company.CompanyService;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import com.raster.moneycraft.foundation.tenant.TenantContextHolder;
import com.raster.moneycraft.reporting.dto.ReportingDtos;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Global TallyPrime Rel 7.2 SmartFind (Alt+F / Ctrl+F) search engine backed by
 * PostgreSQL 18 GIN pg_trgm indexes across active or all accessible companies.
 */
@Service
public class SmartFindService {

    private final RbacAuthorizationService rbacGuard;
    private final CompanyService companyService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public SmartFindService(
        RbacAuthorizationService rbacGuard,
        CompanyService companyService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.rbacGuard = rbacGuard;
        this.companyService = companyService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public ReportingDtos.SmartFindResponseDto search(
        MoneyCraftPrincipal principal,
        UUID activeCompanyId,
        String rawQuery,
        String rawScope,
        Integer rawLimit
    ) {
        long startNs = System.nanoTime();
        rbacGuard.requireCompanyAccess(principal, activeCompanyId);

        String query = rawQuery == null ? "" : rawQuery.trim();
        String scope = normalizeScope(rawScope);
        int limit = rawLimit == null ? 30 : Math.clamp(rawLimit, 1, 100);

        if (query.isEmpty()) {
            return new ReportingDtos.SmartFindResponseDto("", scope, 0L, 0, List.of());
        }

        List<CompanyDtos.CompanySummaryDto> targets;
        if ("ALL_OPEN_COMPANIES".equals(scope)) {
            targets = companyService.listAccessibleCompanies(principal);
        } else {
            targets = List.of(companyService.getCompany(principal, activeCompanyId));
        }

        List<ReportingDtos.SmartFindHitDto> allHits = new ArrayList<>();
        for (CompanyDtos.CompanySummaryDto company : targets) {
            if (allHits.size() >= limit) {
                break;
            }
            int remaining = limit - allHits.size();
            List<ReportingDtos.SmartFindHitDto> companyHits = TenantContextHolder.callWithCompany(
                company.id(),
                () -> businessTxTemplate.execute(tx -> searchSingleCompany(company, query, remaining))
            );
            if (companyHits != null) {
                allHits.addAll(companyHits);
            }
        }

        long tookMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        List<ReportingDtos.SmartFindHitDto> bounded = allHits.size() > limit
            ? allHits.subList(0, limit)
            : allHits;

        return new ReportingDtos.SmartFindResponseDto(
            query,
            scope,
            tookMs,
            bounded.size(),
            bounded
        );
    }

    private List<ReportingDtos.SmartFindHitDto> searchSingleCompany(
        CompanyDtos.CompanySummaryDto company,
        String query,
        int maxHits
    ) {
        UUID cid = company.id();
        String cname = company.legalName();
        int scale = company.currencyScale();
        String pattern = "%" + escapeLike(query) + "%";
        BigDecimal exactNumeric = tryParsePositiveDecimal(query);
        List<ReportingDtos.SmartFindHitDto> hits = new ArrayList<>();

        // 1. Ledgers
        businessJdbcTemplate.query(
            """
            SELECT l.id, l.name, l.alias_code, l.gstin AS party_gstin, g.name AS group_name, l.opening_balance
              FROM mc_ledger l
              LEFT JOIN mc_account_group g
                ON g.company_id = l.company_id
               AND g.id = l.group_id
             WHERE l.company_id = ?
               AND l.active = true
               AND (
                   l.name ILIKE ? ESCAPE '\\'
                   OR COALESCE(l.alias_code, '') ILIKE ? ESCAPE '\\'
                   OR COALESCE(l.gstin, '') ILIKE ? ESCAPE '\\'
               )
             ORDER BY l.name ASC
             LIMIT ?
            """,
            rs -> {
                String name = rs.getString("name");
                String alias = rs.getString("alias_code");
                String gstin = rs.getString("party_gstin");
                String matchedField = containsIgnoreCase(gstin, query)
                    ? "partyGstin"
                    : (containsIgnoreCase(alias, query) ? "alias" : "name");
                String snippet = "partyGstin".equals(matchedField)
                    ? gstin
                    : ("alias".equals(matchedField) ? alias : name);
                UUID id = rs.getObject("id", UUID.class);
                BigDecimal ob = rs.getBigDecimal("opening_balance");
                hits.add(new ReportingDtos.SmartFindHitDto(
                    id.toString(),
                    cid,
                    cname,
                    "MASTER",
                    "LEDGER",
                    name,
                    firstNonBlank(rs.getString("group_name"), "Primary"),
                    matchedField,
                    snippet,
                    ob != null && ob.signum() > 0 ? ExactMoneyValidator.formatExactScale(ob, scale) : null,
                    "/masters/ledgers"
                ));
            },
            cid, pattern, pattern, pattern, maxHits
        );
        if (hits.size() >= maxHits) {
            return hits;
        }

        // 2. Account Groups
        businessJdbcTemplate.query(
            """
            SELECT id, name, alias_code, nature
              FROM mc_account_group
             WHERE company_id = ?
               AND (name ILIKE ? ESCAPE '\\' OR COALESCE(alias_code, '') ILIKE ? ESCAPE '\\')
             ORDER BY name ASC
             LIMIT ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                String name = rs.getString("name");
                hits.add(new ReportingDtos.SmartFindHitDto(
                    id.toString(),
                    cid,
                    cname,
                    "MASTER",
                    "ACCOUNT_GROUP",
                    name,
                    rs.getString("nature"),
                    "name",
                    name,
                    null,
                    "/masters/groups"
                ));
            },
            cid, pattern, pattern, maxHits - hits.size()
        );
        if (hits.size() >= maxHits) {
            return hits;
        }

        // 3. Stock Items
        businessJdbcTemplate.query(
            """
            SELECT id, name, COALESCE(alias_code, part_no) AS sku_code, hsn_sac_code
              FROM mc_stock_item
             WHERE company_id = ?
               AND active = true
               AND (
                   name ILIKE ? ESCAPE '\\'
                   OR COALESCE(alias_code, '') ILIKE ? ESCAPE '\\'
                   OR COALESCE(part_no, '') ILIKE ? ESCAPE '\\'
                   OR COALESCE(hsn_sac_code, '') ILIKE ? ESCAPE '\\'
               )
             ORDER BY name ASC
             LIMIT ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                String name = rs.getString("name");
                String sku = rs.getString("sku_code");
                String hsn = rs.getString("hsn_sac_code");
                String matchedField = containsIgnoreCase(hsn, query)
                    ? "hsnSacCode"
                    : (containsIgnoreCase(sku, query) ? "skuCode" : "name");
                String snippet = "hsnSacCode".equals(matchedField)
                    ? hsn
                    : ("skuCode".equals(matchedField) ? sku : name);
                hits.add(new ReportingDtos.SmartFindHitDto(
                    id.toString(),
                    cid,
                    cname,
                    "MASTER",
                    "STOCK_ITEM",
                    name,
                    firstNonBlank(hsn, sku, "Stock Item"),
                    matchedField,
                    snippet,
                    null,
                    "/inventory/stock-items"
                ));
            },
            cid, pattern, pattern, pattern, pattern, maxHits - hits.size()
        );
        if (hits.size() >= maxHits) {
            return hits;
        }

        // 4. Vouchers (number, narration, supplier invoice no, or amount)
        businessJdbcTemplate.query(
            """
            SELECT j.id, vt.name AS vch_type, j.voucher_number, j.voucher_date,
                   j.supplier_invoice_no, j.narration, j.total_debit
              FROM mc_journal j
              JOIN mc_voucher_type vt
                ON vt.company_id = j.company_id
               AND vt.id = j.voucher_type_id
             WHERE j.company_id = ?
               AND (
                   COALESCE(j.voucher_number, '') ILIKE ? ESCAPE '\\'
                   OR COALESCE(j.supplier_invoice_no, '') ILIKE ? ESCAPE '\\'
                   OR COALESCE(j.narration, '') ILIKE ? ESCAPE '\\'
                   OR (?::numeric IS NOT NULL AND (
                       j.total_debit = ?::numeric
                       OR EXISTS (
                           SELECT 1 FROM mc_entry e
                            WHERE e.company_id = j.company_id
                              AND e.journal_id = j.id
                              AND e.posting_batch_no = j.current_posting_batch_no
                              AND e.is_reversal = false
                              AND e.amount = ?::numeric
                       )
                   ))
               )
             ORDER BY j.voucher_date DESC, j.created_at DESC
             LIMIT ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                String vchType = rs.getString("vch_type");
                String vchNo = firstNonBlank(rs.getString("voucher_number"), "-");
                String supNo = rs.getString("supplier_invoice_no");
                String narr = rs.getString("narration");
                BigDecimal totalDr = rs.getBigDecimal("total_debit");
                String amtStr = ExactMoneyValidator.formatExactScale(
                    totalDr == null ? BigDecimal.ZERO : totalDr, scale
                );
                String matchedField = containsIgnoreCase(vchNo, query)
                    ? "voucherNumber"
                    : (containsIgnoreCase(supNo, query)
                        ? "referenceNo"
                        : (containsIgnoreCase(narr, query) ? "narration" : "amount"));
                String snippet = switch (matchedField) {
                    case "voucherNumber" -> vchNo;
                    case "referenceNo" -> supNo;
                    case "narration" -> narr;
                    default -> amtStr;
                };
                hits.add(new ReportingDtos.SmartFindHitDto(
                    id.toString(),
                    cid,
                    cname,
                    "VOUCHER",
                    "VOUCHER",
                    vchType + " #" + vchNo,
                    rs.getObject("voucher_date").toString(),
                    matchedField,
                    snippet,
                    amtStr,
                    "/vouchers/" + id
                ));
            },
            cid, pattern, pattern, pattern, exactNumeric, exactNumeric, exactNumeric, maxHits - hits.size()
        );
        if (hits.size() >= maxHits) {
            return hits;
        }

        // 5. Bill Allocations
        businessJdbcTemplate.query(
            """
            SELECT ba.id, ba.journal_id, ba.bill_name, ba.amount, l.name AS party_name
              FROM mc_bill_allocation ba
              JOIN mc_ledger l
                ON l.company_id = ba.company_id
               AND l.id = ba.ledger_id
             WHERE ba.company_id = ?
               AND ba.bill_name ILIKE ? ESCAPE '\\'
             ORDER BY ba.created_at DESC
             LIMIT ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                UUID jid = rs.getObject("journal_id", UUID.class);
                String billName = rs.getString("bill_name");
                BigDecimal amt = rs.getBigDecimal("amount");
                hits.add(new ReportingDtos.SmartFindHitDto(
                    id.toString(),
                    cid,
                    cname,
                    "VOUCHER",
                    "BILL_ALLOCATION",
                    "Bill Ref: " + billName,
                    rs.getString("party_name"),
                    "billName",
                    billName,
                    ExactMoneyValidator.formatExactScale(amt, scale),
                    "/vouchers/" + jid
                ));
            },
            cid, pattern, maxHits - hits.size()
        );
        if (hits.size() >= maxHits) {
            return hits;
        }

        // 6. GST Registrations
        businessJdbcTemplate.query(
            """
            SELECT id, registration_name, gstin, state_name
              FROM mc_gst_registration
             WHERE company_id = ?
               AND active = true
               AND (
                   COALESCE(gstin, '') ILIKE ? ESCAPE '\\'
                   OR registration_name ILIKE ? ESCAPE '\\'
               )
             ORDER BY registration_name ASC
             LIMIT ?
            """,
            rs -> {
                UUID id = rs.getObject("id", UUID.class);
                String gstin = firstNonBlank(rs.getString("gstin"), rs.getString("registration_name"));
                hits.add(new ReportingDtos.SmartFindHitDto(
                    id.toString(),
                    cid,
                    cname,
                    "STATUTORY",
                    "GST_REGISTRATION",
                    gstin,
                    rs.getString("state_name"),
                    "gstin",
                    gstin,
                    null,
                    "/statutory/gst-registrations"
                ));
            },
            cid, pattern, pattern, maxHits - hits.size()
        );

        return hits;
    }

    private static String normalizeScope(String rawScope) {
        if (rawScope == null || rawScope.isBlank()) {
            return "ACTIVE";
        }
        String up = rawScope.trim().toUpperCase(Locale.ROOT);
        if ("ALL_OPEN_COMPANIES".equals(up) || "ALL".equals(up)) {
            return "ALL_OPEN_COMPANIES";
        }
        return "ACTIVE";
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static BigDecimal tryParsePositiveDecimal(String raw) {
        String cleaned = raw.replace(",", "").replace("\u20B9", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            BigDecimal bd = new BigDecimal(cleaned);
            return bd.signum() >= 0 && bd.scale() <= 6 ? bd : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
