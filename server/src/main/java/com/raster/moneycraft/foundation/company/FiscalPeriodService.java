package com.raster.moneycraft.foundation.company;

import com.raster.moneycraft.foundation.audit.AuditChainService;
import com.raster.moneycraft.foundation.authority.AuthorityGuard;
import com.raster.moneycraft.foundation.error.MoneyCraftException;
import com.raster.moneycraft.foundation.events.SseBroadcasterService;
import com.raster.moneycraft.foundation.security.CompanyRole;
import com.raster.moneycraft.foundation.security.MoneyCraftPrincipal;
import com.raster.moneycraft.foundation.security.RbacAuthorizationService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant-isolated fiscal year and accounting period service (OPEN, SOFT_LOCKED, CLOSED).
 */
@Service
public class FiscalPeriodService {

    private static final Set<String> PERMITTED_STATES = Set.of("OPEN", "SOFT_LOCKED", "CLOSED");

    private final AuthorityGuard authorityGuard;
    private final RbacAuthorizationService rbacGuard;
    private final AuditChainService mcAuditChainService;
    private final SseBroadcasterService sseBroadcaster;
    private final JdbcTemplate businessJdbcTemplate;
    private final TransactionTemplate businessTxTemplate;

    public FiscalPeriodService(
        AuthorityGuard authorityGuard,
        RbacAuthorizationService rbacGuard,
        AuditChainService mcAuditChainService,
        SseBroadcasterService sseBroadcaster,
        @Qualifier("businessJdbcTemplate") JdbcTemplate businessJdbcTemplate,
        @Qualifier("transactionManager") PlatformTransactionManager businessTransactionManager
    ) {
        this.authorityGuard = authorityGuard;
        this.rbacGuard = rbacGuard;
        this.mcAuditChainService = mcAuditChainService;
        this.sseBroadcaster = sseBroadcaster;
        this.businessJdbcTemplate = businessJdbcTemplate;
        this.businessTxTemplate = new TransactionTemplate(businessTransactionManager);
    }

    public List<CompanyDtos.FiscalYearDto> listFiscalYears(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> {
            List<CompanyDtos.AccountingPeriodDto> allPeriods = queryTenantPeriods();
            Map<UUID, List<CompanyDtos.AccountingPeriodDto>> grouped = new LinkedHashMap<>();
            for (CompanyDtos.AccountingPeriodDto period : allPeriods) {
                grouped.computeIfAbsent(period.fiscalYearId(), k -> new ArrayList<>()).add(period);
            }

            return businessJdbcTemplate.query(
                """
                SELECT id, code, display_label, start_date, end_date, is_closed, version_no
                  FROM mc_fiscal_year
                 ORDER BY start_date ASC
                """,
                (rs, rowNum) -> {
                    UUID fyId = rs.getObject("id", UUID.class);
                    String fyCode = rs.getString("code");
                    boolean closed = rs.getBoolean("is_closed");
                    List<CompanyDtos.AccountingPeriodDto> nested = grouped.getOrDefault(fyId, List.of());
                    return new CompanyDtos.FiscalYearDto(
                        fyId,
                        fyId,
                        fyCode,
                        fyCode,
                        rs.getString("display_label"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        closed,
                        computeEffectiveYearState(closed, nested),
                        rs.getLong("version_no"),
                        nested
                    );
                }
            );
        });
    }

    public List<CompanyDtos.AccountingPeriodDto> listPeriods(MoneyCraftPrincipal principal, UUID companyId) {
        rbacGuard.requireCompanyAccess(principal, companyId);
        return businessTxTemplate.execute(tx -> queryTenantPeriods());
    }

    public CompanyDtos.FiscalYearDto createFiscalYear(
        MoneyCraftPrincipal actor,
        UUID companyId,
        CompanyDtos.CreateFiscalYearRequest request
    ) {
        authorityGuard.assertWritable();
        rbacGuard.requireCompanyRole(actor, companyId, Set.of(CompanyRole.COMPANY_ADMIN));

        if (request == null || request.startDate() == null || request.endDate() == null) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-FY-DATES-REQUIRED",
                "Fiscal Year Dates Required",
                "Both startDate and endDate are required."
            );
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-FY-DATE-ORDER",
                "Invalid Fiscal Year Date Span",
                "endDate must be on or after startDate."
            );
        }

        String fyCode = (request.code() == null || request.code().isBlank())
            ? "FY" + request.startDate().getYear() + "-" + String.format("%02d", request.endDate().getYear() % 100)
            : request.code().trim().toUpperCase();
        String label = (request.displayLabel() == null || request.displayLabel().isBlank())
            ? "FY " + request.startDate().getYear() + "-" + String.format("%02d", request.endDate().getYear() % 100)
            : request.displayLabel().trim();

        return businessTxTemplate.execute(tx -> {
            CompanyDtos.FiscalYearDto created = seedFiscalYearWithMonthlyPeriodsInCurrentTx(
                companyId,
                actor.principalId(),
                fyCode,
                label,
                request.startDate(),
                request.endDate()
            );

            mcAuditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "FISCAL_YEAR_CREATE",
                "FISCAL_YEAR",
                created.id().toString(),
                "SUCCESS",
                null,
                Map.of(
                    "code", created.code(),
                    "startDate", created.startDate().toString(),
                    "endDate", created.endDate().toString(),
                    "periodCount", created.periods().size()
                )
            );

            return created;
        });
    }

    public CompanyDtos.AccountingPeriodDto transitionPeriodState(
        MoneyCraftPrincipal actor,
        UUID companyId,
        UUID targetId,
        String ifMatchHeader,
        CompanyDtos.PatchPeriodStateRequest request
    ) {
        authorityGuard.assertWritable();
        rbacGuard.requireCompanyRole(actor, companyId, Set.of(CompanyRole.COMPANY_ADMIN));

        if (request == null || request.state() == null || request.state().isBlank()) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PERIOD-STATE-REQUIRED",
                "Target Period State Required",
                "Specify state as OPEN, SOFT_LOCKED, or CLOSED."
            );
        }
        String desiredState = request.state().trim().toUpperCase();
        if (!PERMITTED_STATES.contains(desiredState)) {
            throw new MoneyCraftException(
                HttpStatus.BAD_REQUEST,
                "MC-PERIOD-STATE-INVALID",
                "Invalid Accounting Period State",
                "Accounting period state must be OPEN, SOFT_LOCKED, or CLOSED."
            );
        }
        String lockNote = (request.reason() == null || request.reason().isBlank())
            ? null
            : request.reason().trim();

        CompanyDtos.AccountingPeriodDto result = businessTxTemplate.execute(tx -> {
            List<CompanyDtos.AccountingPeriodDto> periodRows = businessJdbcTemplate.query(
                """
                SELECT id, fiscal_year_id, period_no, code, name, start_date, end_date,
                       state, locked_reason, version_no
                  FROM mc_period
                 WHERE company_id = ? AND id = ?
                   FOR UPDATE
                """,
                this::mapPeriodRow,
                companyId,
                targetId
            );

            if (!periodRows.isEmpty()) {
                CompanyDtos.AccountingPeriodDto before = periodRows.getFirst();
                if (ifMatchHeader != null && !ifMatchHeader.isBlank()) {
                    long expectedVer = CompanyService.parseExpectedVersion(ifMatchHeader, null);
                    if (expectedVer != before.versionNo()) {
                        throw new MoneyCraftException(
                            HttpStatus.PRECONDITION_FAILED,
                            "MC-CONCURRENCY-STALE-VERSION",
                            "Stale Period Version",
                            "Expected period version v" + expectedVer + " but found v" + before.versionNo() + "."
                        );
                    }
                }

                long nextVer = before.versionNo() + 1L;
                String effectiveReason = "OPEN".equals(desiredState) ? null : lockNote;
                businessJdbcTemplate.update(
                    """
                    UPDATE mc_period
                       SET state = ?,
                           locked_reason = ?,
                           state_changed_by = ?,
                           state_changed_at = clock_timestamp(),
                           version_no = ?,
                           updated_at = clock_timestamp()
                     WHERE company_id = ? AND id = ?
                    """,
                    desiredState,
                    effectiveReason,
                    actor.principalId(),
                    nextVer,
                    companyId,
                    targetId
                );

                CompanyDtos.AccountingPeriodDto after = new CompanyDtos.AccountingPeriodDto(
                    before.id(),
                    before.fiscalYearId(),
                    before.periodNo(),
                    before.code(),
                    before.periodCode(),
                    before.name(),
                    before.startDate(),
                    before.endDate(),
                    desiredState,
                    effectiveReason,
                    nextVer
                );

                mcAuditChainService.appendCompanyEvent(
                    companyId,
                    actor.principalId(),
                    actor.kind(),
                    "PERIOD_STATE_CHANGE",
                    "PERIOD",
                    after.id().toString(),
                    "SUCCESS",
                    null,
                    Map.of(
                        "code", after.code(),
                        "previousState", before.state(),
                        "newState", desiredState
                    )
                );
                return after;
            }

            List<LockedYearRow> fyRows = businessJdbcTemplate.query(
                """
                SELECT id, code, display_label, start_date, end_date, version_no
                  FROM mc_fiscal_year
                 WHERE company_id = ? AND id = ?
                   FOR UPDATE
                """,
                (rs, rowNum) -> new LockedYearRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("display_label"),
                    rs.getObject("start_date", LocalDate.class),
                    rs.getObject("end_date", LocalDate.class),
                    rs.getLong("version_no")
                ),
                companyId,
                targetId
            );

            if (fyRows.isEmpty()) {
                throw new MoneyCraftException(
                    HttpStatus.NOT_FOUND,
                    "MC-PERIOD-NOT-FOUND",
                    "Accounting Period Not Found",
                    "Period " + targetId + " was not found in company " + companyId + "."
                );
            }

            LockedYearRow fy = fyRows.getFirst();
            long nextFyVer = fy.versionNo() + 1L;
            String effectiveReason = "OPEN".equals(desiredState) ? null : lockNote;

            businessJdbcTemplate.update(
                """
                UPDATE mc_fiscal_year
                   SET is_closed = ?,
                       version_no = ?,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND id = ?
                """,
                "CLOSED".equals(desiredState),
                nextFyVer,
                companyId,
                fy.id()
            );
            businessJdbcTemplate.update(
                """
                UPDATE mc_period
                   SET state = ?,
                       locked_reason = ?,
                       state_changed_by = ?,
                       state_changed_at = clock_timestamp(),
                       version_no = version_no + 1,
                       updated_at = clock_timestamp()
                 WHERE company_id = ? AND fiscal_year_id = ?
                """,
                desiredState,
                effectiveReason,
                actor.principalId(),
                companyId,
                fy.id()
            );

            mcAuditChainService.appendCompanyEvent(
                companyId,
                actor.principalId(),
                actor.kind(),
                "FISCAL_YEAR_STATE_CHANGE",
                "FISCAL_YEAR",
                fy.id().toString(),
                "SUCCESS",
                null,
                Map.of("code", fy.code(), "newState", desiredState)
            );

            return new CompanyDtos.AccountingPeriodDto(
                fy.id(),
                fy.id(),
                1,
                fy.code(),
                fy.code(),
                fy.displayLabel(),
                fy.startDate(),
                fy.endDate(),
                desiredState,
                effectiveReason,
                nextFyVer
            );
        });

        sseBroadcaster.broadcast(
            "PERIOD_STATE_CHANGED",
            companyId,
            Map.of(
                "companyId", companyId.toString(),
                "periodId", result.id().toString(),
                "state", result.state()
            )
        );
        return result;
    }

    public CompanyDtos.FiscalYearDto seedFiscalYearWithMonthlyPeriodsInCurrentTx(
        UUID companyId,
        UUID actorPrincipalId,
        String fyCode,
        String displayLabel,
        LocalDate fyStart,
        LocalDate fyEnd
    ) {
        UUID fyId = UUID.randomUUID();
        businessJdbcTemplate.update(
            """
            INSERT INTO mc_fiscal_year (
                id, company_id, code, display_label, start_date, end_date, is_closed, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, false, ?)
            """,
            fyId,
            companyId,
            fyCode,
            displayLabel,
            fyStart,
            fyEnd,
            actorPrincipalId
        );

        List<CompanyDtos.AccountingPeriodDto> seeded = new ArrayList<>();
        LocalDate cursor = fyStart;
        int seq = 1;

        while (!cursor.isAfter(fyEnd)) {
            YearMonth ym = YearMonth.from(cursor);
            LocalDate lastOfMonth = ym.atEndOfMonth();
            LocalDate sliceEnd = lastOfMonth.isAfter(fyEnd) ? fyEnd : lastOfMonth;

            String pCode = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            String pName = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + ym.getYear();
            UUID periodId = UUID.randomUUID();

            businessJdbcTemplate.update(
                """
                INSERT INTO mc_period (
                    id, company_id, fiscal_year_id, period_no, code, name,
                    start_date, end_date, state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
                """,
                periodId,
                companyId,
                fyId,
                (short) seq,
                pCode,
                pName,
                cursor,
                sliceEnd
            );

            seeded.add(new CompanyDtos.AccountingPeriodDto(
                periodId,
                fyId,
                seq,
                pCode,
                pCode,
                pName,
                cursor,
                sliceEnd,
                "OPEN",
                null,
                1L
            ));

            cursor = sliceEnd.plusDays(1);
            seq++;
        }

        return new CompanyDtos.FiscalYearDto(
            fyId,
            fyId,
            fyCode,
            fyCode,
            displayLabel,
            fyStart,
            fyEnd,
            false,
            "OPEN",
            1L,
            List.copyOf(seeded)
        );
    }

    private List<CompanyDtos.AccountingPeriodDto> queryTenantPeriods() {
        return businessJdbcTemplate.query(
            """
            SELECT id, fiscal_year_id, period_no, code, name, start_date, end_date,
                   state, locked_reason, version_no
              FROM mc_period
             ORDER BY start_date ASC, period_no ASC
            """,
            this::mapPeriodRow
        );
    }

    private CompanyDtos.AccountingPeriodDto mapPeriodRow(java.sql.ResultSet rs, int rowNum)
        throws java.sql.SQLException {
        String code = rs.getString("code");
        return new CompanyDtos.AccountingPeriodDto(
            rs.getObject("id", UUID.class),
            rs.getObject("fiscal_year_id", UUID.class),
            rs.getShort("period_no"),
            code,
            code,
            rs.getString("name"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getString("state"),
            rs.getString("locked_reason"),
            rs.getLong("version_no")
        );
    }

    private static String computeEffectiveYearState(
        boolean isClosed,
        List<CompanyDtos.AccountingPeriodDto> periods
    ) {
        if (isClosed) {
            return "CLOSED";
        }
        if (periods.isEmpty()) {
            return "OPEN";
        }
        if (periods.stream().allMatch(p -> "CLOSED".equals(p.state()))) {
            return "CLOSED";
        }
        if (periods.stream().anyMatch(p -> "SOFT_LOCKED".equals(p.state()))) {
            return "SOFT_LOCKED";
        }
        return "OPEN";
    }

    private record LockedYearRow(
        UUID id,
        String code,
        String displayLabel,
        LocalDate startDate,
        LocalDate endDate,
        long versionNo
    ) {
    }
}
