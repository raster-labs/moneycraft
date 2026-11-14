package com.raster.moneycraft.accounting.service;

import com.raster.moneycraft.accounting.dto.CurrencyForexDtos;
import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Multi-currency master and date-effective forex rate service.
 */
@Service
public class CurrencyForexService {

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final CompanyMasterSequenceHelper sequenceHelper;
    private final AuditChainService auditChainService;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public CurrencyForexService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        CompanyMasterSequenceHelper sequenceHelper,
        AuditChainService auditChainService,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.sequenceHelper = sequenceHelper;
        this.auditChainService = auditChainService;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<CurrencyForexDtos.CurrencyResponse> listCurrencies(
        MoneyCraftPrincipal principal,
        UUID companyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessJdbcTemplate.query(
            currencySelectSql("c.company_id = ?") + " ORDER BY c.is_base_currency DESC, c.iso_code ASC",
            this::mapCurrencyRow,
            companyId
        );
    }

    public CurrencyForexDtos.CurrencyResponse getCurrency(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID currencyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return requireCurrencyById(companyId, currencyId);
    }

    public CurrencyForexDtos.CurrencyResponse createCurrency(
        MoneyCraftPrincipal actor,
        UUID companyId,
        CurrencyForexDtos.CreateOrUpdateCurrencyRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        NormalizedCurrencySpec spec = validateAndNormalizeCurrency(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForCreate(companyId, false);

            Integer dup = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_currency WHERE company_id = ? AND iso_code = ?",
                Integer.class,
                companyId,
                spec.isoCode()
            );
            if (dup != null && dup > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-CURRENCY-DUPLICATE",
                    "Duplicate Currency ISO Code",
                    "Currency '" + spec.isoCode() + "' already exists in this company."
                );
            }

            UUID currencyId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_currency (
                    id, company_id, iso_code, symbol, formal_name,
                    decimal_places, decimal_symbol, show_amount_in_millions,
                    suffix_symbol_to_amount, add_space_between_amount_and_symbol,
                    is_base_currency, active, master_seq_id, alter_id, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?, ?, 1)
                """,
                currencyId,
                companyId,
                spec.isoCode(),
                spec.symbol(),
                spec.formalName(),
                (short) spec.decimalPlaces(),
                spec.decimalSymbol(),
                spec.showAmountInMillions(),
                spec.suffixSymbolToAmount(),
                spec.addSpaceBetweenAmountAndSymbol(),
                spec.active(),
                seq.masterSeqId(),
                seq.alterId()
            );

            CurrencyForexDtos.CurrencyResponse created = requireCurrencyById(companyId, currencyId);
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "CURRENCY_CREATE",
                "CURRENCY",
                currencyId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "isoCode", created.isoCode(),
                    "formalName", created.formalName(),
                    "decimalPlaces", created.decimalPlaces()
                )
            );
            return created;
        });
    }

    public CurrencyForexDtos.CurrencyResponse updateCurrency(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID currencyId,
        String ifMatchHeader,
        CurrencyForexDtos.CreateOrUpdateCurrencyRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);
        long expectedVersion = CompanyMasterSequenceHelper.requireIfMatchVersion(
            ifMatchHeader,
            request == null ? null : request.versionNo()
        );
        NormalizedCurrencySpec spec = validateAndNormalizeCurrency(request);

        return businessTxTemplate.execute(tx -> {
            CompanyMasterSequenceHelper.AllocatedMasterSeq seq =
                sequenceHelper.allocateForAlterOrDelete(companyId, false);

            CurrencyForexDtos.CurrencyResponse before = lockCurrencyById(companyId, currencyId);
            CompanyMasterSequenceHelper.assertExpectedVersion(expectedVersion, before.versionNo(), "currency");

            if (before.isBaseCurrency() && !before.isoCode().equals(spec.isoCode())) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-BASE-CURRENCY-IMMUTABLE",
                    "Base Currency ISO Code Immutable",
                    "Base currency ISO code '" + before.isoCode() + "' cannot be altered."
                );
            }

            Integer dup = businessJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mc_currency WHERE company_id = ? AND iso_code = ? AND id <> ?",
                Integer.class,
                companyId,
                spec.isoCode(),
                currencyId
            );
            if (dup != null && dup > 0) {
                throw new MoneyCraftException(
                    HttpStatus.CONFLICT,
                    "MC-ACC-CURRENCY-DUPLICATE",
                    "Duplicate Currency ISO Code",
                    "Currency '" + spec.isoCode() + "' already exists in this company."
                );
            }

            long nextVersion = before.versionNo() + 1L;
            businessJdbcTemplate.update(
                """
                UPDATE mc_currency
                   SET iso_code = ?,
                       symbol = ?,
                       formal_name = ?,
                       decimal_places = ?,
                       decimal_symbol = ?,
                       show_amount_in_millions = ?,
                       suffix_symbol_to_amount = ?,
                       add_space_between_amount_and_symbol = ?,
                       active = ?,
                       alter_id = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                spec.isoCode(),
                spec.symbol(),
                spec.formalName(),
                (short) spec.decimalPlaces(),
                spec.decimalSymbol(),
                spec.showAmountInMillions(),
                spec.suffixSymbolToAmount(),
                spec.addSpaceBetweenAmountAndSymbol(),
                before.isBaseCurrency() || spec.active(),
                seq.alterId(),
                nextVersion,
                companyId,
                currencyId
            );

            CurrencyForexDtos.CurrencyResponse after = requireCurrencyById(companyId, currencyId);
            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "CURRENCY_ALTER",
                "CURRENCY",
                currencyId.toString(),
                "SUCCESS",
                null,
                Map.of(
                    "isoCode", after.isoCode(),
                    "versionNo", after.versionNo()
                )
            );
            return after;
        });
    }

    public List<CurrencyForexDtos.ForexRateResponse> listForexRates(
        MoneyCraftPrincipal principal,
        UUID companyId,
        UUID currencyId
    ) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        requireCurrencyById(companyId, currencyId);
        return businessJdbcTemplate.query(
            """
            SELECT r.id, r.company_id, r.currency_id, trim(c.iso_code) AS iso_code,
                   r.effective_date, r.standard_rate, r.selling_rate, r.buying_rate, r.version_no
              FROM mc_forex_rate r
              JOIN mc_currency c
                ON c.company_id = r.company_id AND c.id = r.currency_id
             WHERE r.company_id = ? AND r.currency_id = ?
             ORDER BY r.effective_date DESC
            """,
            this::mapForexRateRow,
            companyId,
            currencyId
        );
    }

    public CurrencyForexDtos.ForexRateResponse upsertForexRate(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID currencyId,
        CurrencyForexDtos.UpsertForexRateRequest request
    ) {
        authorityGuard.assertWritable();
        CompanyMasterSequenceHelper.requireMasterWriteRole(rbacGuard, actor, companyId);

        if (request == null || request.effectiveDate() == null) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-FOREX-DATE-REQUIRED",
                "Effective Date Required",
                "Forex rate 'effectiveDate' is required.",
                Map.of("effectiveDate", "Required YYYY-MM-DD date")
            );
        }

        BigDecimal stdRate = ExactMoneyValidator.parseAndValidateBoundedDecimal(
            request.standardRate(),
            6,
            null,
            null,
            true,
            "standardRate"
        );
        BigDecimal sellRate = request.sellingRate() == null || request.sellingRate().isBlank()
            ? null
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                request.sellingRate(),
                6,
                null,
                null,
                true,
                "sellingRate"
            );
        BigDecimal buyRate = request.buyingRate() == null || request.buyingRate().isBlank()
            ? null
            : ExactMoneyValidator.parseAndValidateBoundedDecimal(
                request.buyingRate(),
                6,
                null,
                null,
                true,
                "buyingRate"
            );

        return businessTxTemplate.execute(tx -> {
            sequenceHelper.allocateForAlterOrDelete(companyId, false);
            CurrencyForexDtos.CurrencyResponse currency = requireCurrencyById(companyId, currencyId);
            if (currency.isBaseCurrency()) {
                throw new MoneyCraftException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "MC-ACC-FOREX-BASE-CURRENCY",
                    "Forex Rate Not Applicable to Base Currency",
                    "Exchange rates are defined on foreign currencies, not on base currency " + currency.isoCode() + "."
                );
            }

            UUID candidateId = UUID.randomUUID();
            businessJdbcTemplate.update(
                """
                INSERT INTO mc_forex_rate (
                    id, company_id, currency_id, effective_date,
                    standard_rate, selling_rate, buying_rate, version_no
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (company_id, currency_id, effective_date)
                DO UPDATE SET
                    standard_rate = EXCLUDED.standard_rate,
                    selling_rate = EXCLUDED.selling_rate,
                    buying_rate = EXCLUDED.buying_rate,
                    version_no = mc_forex_rate.version_no + 1,
                    updated_at = clock_timestamp()
                """,
                candidateId,
                companyId,
                currencyId,
                request.effectiveDate(),
                stdRate,
                sellRate,
                buyRate
            );

            CurrencyForexDtos.ForexRateResponse saved = businessJdbcTemplate.queryForObject(
                """
                SELECT r.id, r.company_id, r.currency_id, trim(c.iso_code) AS iso_code,
                       r.effective_date, r.standard_rate, r.selling_rate, r.buying_rate, r.version_no
                  FROM mc_forex_rate r
                  JOIN mc_currency c
                    ON c.company_id = r.company_id AND c.id = r.currency_id
                 WHERE r.company_id = ? AND r.currency_id = ? AND r.effective_date = ?
                """,
                this::mapForexRateRow,
                companyId,
                currencyId,
                request.effectiveDate()
            );
            if (saved == null) {
                throw new IllegalStateException("Upserted forex rate could not be retrieved");
            }

            auditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "FOREX_RATE_UPSERT",
                "FOREX_RATE",
                saved.id().toString(),
                "SUCCESS",
                null,
                Map.of(
                    "isoCode", saved.isoCode(),
                    "effectiveDate", saved.effectiveDate().toString(),
                    "standardRate", saved.standardRate(),
                    "versionNo", saved.versionNo()
                )
            );
            return saved;
        });
    }

    private CurrencyForexDtos.CurrencyResponse requireCurrencyById(UUID companyId, UUID currencyId) {
        List<CurrencyForexDtos.CurrencyResponse> rows = businessJdbcTemplate.query(
            currencySelectSql("c.company_id = ? AND c.id = ?"),
            this::mapCurrencyRow,
            companyId,
            currencyId
        );
        if (rows.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-CURRENCY-NOT-FOUND",
                "Currency Not Found",
                "Currency " + currencyId + " does not exist."
            );
        }
        return rows.getFirst();
    }

    private CurrencyForexDtos.CurrencyResponse lockCurrencyById(UUID companyId, UUID currencyId) {
        List<UUID> locked = businessJdbcTemplate.query(
            "SELECT id FROM mc_currency WHERE company_id = ? AND id = ? FOR UPDATE",
            (rs, rowNum) -> rs.getObject("id", UUID.class),
            companyId,
            currencyId
        );
        if (locked.isEmpty()) {
            throw new MoneyCraftException(
                HttpStatus.NOT_FOUND,
                "MC-ACC-CURRENCY-NOT-FOUND",
                "Currency Not Found",
                "Currency " + currencyId + " does not exist."
            );
        }
        return requireCurrencyById(companyId, currencyId);
    }

    private static String currencySelectSql(String whereClause) {
        return """
            SELECT c.id, c.company_id, trim(c.iso_code) AS iso_code, c.symbol, c.formal_name,
                   c.decimal_places, c.decimal_symbol, c.show_amount_in_millions,
                   c.suffix_symbol_to_amount, c.add_space_between_amount_and_symbol,
                   c.is_base_currency, c.active, c.master_seq_id, c.alter_id, c.version_no
              FROM mc_currency c
             WHERE %s
            """.formatted(whereClause);
    }

    private CurrencyForexDtos.CurrencyResponse mapCurrencyRow(ResultSet rs, int rowNum) throws SQLException {
        return new CurrencyForexDtos.CurrencyResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getString("iso_code"),
            rs.getString("symbol"),
            rs.getString("formal_name"),
            rs.getShort("decimal_places"),
            rs.getString("decimal_symbol"),
            rs.getBoolean("show_amount_in_millions"),
            rs.getBoolean("suffix_symbol_to_amount"),
            rs.getBoolean("add_space_between_amount_and_symbol"),
            rs.getBoolean("is_base_currency"),
            rs.getBoolean("active"),
            rs.getLong("master_seq_id"),
            rs.getLong("alter_id"),
            rs.getLong("version_no")
        );
    }

    private CurrencyForexDtos.ForexRateResponse mapForexRateRow(ResultSet rs, int rowNum) throws SQLException {
        return new CurrencyForexDtos.ForexRateResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("company_id", UUID.class),
            rs.getObject("currency_id", UUID.class),
            rs.getString("iso_code"),
            rs.getObject("effective_date", LocalDate.class),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("standard_rate")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("selling_rate")),
            ExactMoneyValidator.formatPlainDecimal(rs.getBigDecimal("buying_rate")),
            rs.getLong("version_no")
        );
    }

    private static NormalizedCurrencySpec validateAndNormalizeCurrency(
        CurrencyForexDtos.CreateOrUpdateCurrencyRequest req
    ) {
        if (req == null || req.isoCode() == null || req.isoCode().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-CURRENCY-ISO-REQUIRED",
                "Currency ISO Code Required",
                "Currency 'isoCode' must be a 3-letter uppercase ISO 4217 code."
            );
        }
        String iso = req.isoCode().trim().toUpperCase(Locale.ROOT);
        if (!iso.matches("^[A-Z]{3}$")) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-CURRENCY-INVALID",
                "Invalid Currency ISO Code",
                "Currency 'isoCode' must match ^[A-Z]{3}$."
            );
        }
        String symbol = req.symbol() == null || req.symbol().isBlank() ? iso : req.symbol().trim();
        String formalName = req.formalName() == null || req.formalName().isBlank() ? iso : req.formalName().trim();
        int decimals = req.decimalPlaces() == null ? 2 : req.decimalPlaces();
        if (decimals < 0 || decimals > 6) {
            throw new MoneyCraftException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "MC-ACC-CURRENCY-DECIMALS-INVALID",
                "Invalid Currency Decimal Places",
                "decimalPlaces must be between 0 and 6."
            );
        }
        String decimalSymbol = req.decimalSymbol() == null || req.decimalSymbol().isBlank()
            ? ("INR".equals(iso) ? "paise" : "cents")
            : req.decimalSymbol().trim();

        return new NormalizedCurrencySpec(
            iso,
            symbol,
            formalName,
            decimals,
            decimalSymbol,
            Boolean.TRUE.equals(req.showAmountInMillions()),
            Boolean.TRUE.equals(req.suffixSymbolToAmount()),
            req.addSpaceBetweenAmountAndSymbol() == null || req.addSpaceBetweenAmountAndSymbol(),
            req.active() == null || req.active()
        );
    }

    private record NormalizedCurrencySpec(
        String isoCode,
        String symbol,
        String formalName,
        int decimalPlaces,
        String decimalSymbol,
        boolean showAmountInMillions,
        boolean suffixSymbolToAmount,
        boolean addSpaceBetweenAmountAndSymbol,
        boolean active
    ) {
    }
}
