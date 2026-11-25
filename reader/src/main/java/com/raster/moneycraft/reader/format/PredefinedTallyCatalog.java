package com.raster.moneycraft.reader.format;

import com.raster.moneycraft.reader.model.RawGroup;
import com.raster.moneycraft.reader.model.RawVoucherType;

import java.util.List;
import java.util.Set;

/**
 * Reference system catalog of 28 predefined accounting groups and 24 active standard voucher types.
 */
public final class PredefinedTallyCatalog {

    /**
     * Built-in baseTypeId values in Manager.1800 (classId = 10, fieldId = 0x0FA2) that are dormant
     * system planning / statutory adjustment placeholders unless custom-created.
     */
    private static final Set<Integer> DORMANT_BUILTIN_VOUCHER_BASE_IDS = Set.of(
            19, 20, 21, 22, 23, 30, 31, 32, 33
    );

    private PredefinedTallyCatalog() {
    }

    public static boolean isActiveBuiltinVoucherBaseId(int baseTypeId) {
        return baseTypeId <= 0 || !DORMANT_BUILTIN_VOUCHER_BASE_IDS.contains(baseTypeId);
    }

    public static List<RawGroup> canonical28Groups(String companyGuid) {
        String prefix = (companyGuid == null || companyGuid.isBlank()) ? "00000000-0000-0000-0000-000000000000" : companyGuid;
        return List.of(
                group(prefix, 1, "Capital Account", "Primary", false, false, false, false),
                group(prefix, 2, "Reserves & Surplus", "Capital Account", false, false, false, false),
                group(prefix, 3, "Loans (Liability)", "Primary", false, false, false, false),
                group(prefix, 4, "Bank OD A/c", "Loans (Liability)", false, false, false, false),
                group(prefix, 5, "Secured Loans", "Loans (Liability)", false, false, false, false),
                group(prefix, 6, "Unsecured Loans", "Loans (Liability)", false, false, false, false),
                group(prefix, 7, "Current Liabilities", "Primary", false, false, false, false),
                group(prefix, 8, "Duties & Taxes", "Current Liabilities", false, false, false, false),
                group(prefix, 9, "Provisions", "Current Liabilities", false, false, false, false),
                group(prefix, 10, "Sundry Creditors", "Current Liabilities", false, false, false, true),
                group(prefix, 11, "Fixed Assets", "Primary", false, true, false, false),
                group(prefix, 12, "Investments", "Primary", false, true, false, false),
                group(prefix, 13, "Current Assets", "Primary", false, true, false, false),
                group(prefix, 14, "Bank Accounts", "Current Assets", false, true, false, false),
                group(prefix, 15, "Cash-in-Hand", "Current Assets", false, true, false, false),
                group(prefix, 16, "Deposits (Asset)", "Current Assets", false, true, false, false),
                group(prefix, 17, "Loans & Advances (Asset)", "Current Assets", false, true, false, false),
                group(prefix, 18, "Stock-in-Hand", "Current Assets", false, true, false, false),
                group(prefix, 19, "Sundry Debtors", "Current Assets", false, true, false, true),
                group(prefix, 20, "Branch / Divisions", "Primary", false, false, false, false),
                group(prefix, 21, "Misc. Expenses (ASSET)", "Primary", false, true, false, false),
                group(prefix, 22, "Suspense A/c", "Primary", false, false, false, false),
                group(prefix, 23, "Sales Accounts", "Primary", true, false, true, false),
                group(prefix, 24, "Purchase Accounts", "Primary", true, true, true, false),
                group(prefix, 25, "Direct Incomes", "Primary", true, false, true, false),
                group(prefix, 26, "Direct Expenses", "Primary", true, true, true, false),
                group(prefix, 27, "Indirect Incomes", "Primary", true, false, false, false),
                group(prefix, 28, "Indirect Expenses", "Primary", true, true, false, false)
        );
    }

    public static List<RawVoucherType> canonical24VoucherTypes(String companyGuid) {
        String prefix = (companyGuid == null || companyGuid.isBlank()) ? "00000000-0000-0000-0000-000000000000" : companyGuid;
        return List.of(
                vtype(prefix, 1, "Attendance", "Attd", 24),
                vtype(prefix, 2, "Contra", "Ctra", 1),
                vtype(prefix, 3, "Credit Note", "C/Note", 5),
                vtype(prefix, 4, "Debit Note", "D/Note", 6),
                vtype(prefix, 5, "Delivery Note", "Dely Note", 9),
                vtype(prefix, 6, "Job Work In Order", "Job Wrk In Ordr", 26),
                vtype(prefix, 7, "Job Work Out Order", "Job Wrk Out Ordr", 27),
                vtype(prefix, 8, "Journal", "Jrnl", 4),
                vtype(prefix, 9, "Material In", "Mat In", 29),
                vtype(prefix, 10, "Material Out", "Mat Out", 28),
                vtype(prefix, 11, "Memorandum", "Memo", 15),
                vtype(prefix, 12, "Payment", "Pymt", 2),
                vtype(prefix, 13, "Payroll", "Pyrl", 25),
                vtype(prefix, 14, "Physical Stock", "Phy Stk", 13),
                vtype(prefix, 15, "Purchase", "Purc", 8),
                vtype(prefix, 16, "Purchase Order", "Purc Order", 18),
                vtype(prefix, 17, "Receipt", "Rcpt", 3),
                vtype(prefix, 18, "Receipt Note", "Rcpt Note", 10),
                vtype(prefix, 19, "Rejections In", "Rej In", 12),
                vtype(prefix, 20, "Rejections Out", "Rej Out", 11),
                vtype(prefix, 21, "Reversing Journal", "Rev Jrnl", 16),
                vtype(prefix, 22, "Sales", "Sale", 7),
                vtype(prefix, 23, "Sales Order", "Sales Ordr", 17),
                vtype(prefix, 24, "Stock Journal", "Stk Jrnl", 14)
        );
    }

    private static RawGroup group(
            String prefix,
            int id,
            String name,
            String parent,
            boolean revenue,
            boolean deemedPositive,
            boolean affectsGp,
            boolean subledger) {
        return new RawGroup(
                id,
                String.format("%s-%08x", prefix, id),
                name,
                parent,
                revenue,
                deemedPositive,
                affectsGp,
                subledger,
                id);
    }

    private static RawVoucherType vtype(String prefix, int id, String name, String abbr, int baseTypeId) {
        return new RawVoucherType(
                id,
                String.format("%s-%08x", prefix, 100 + id),
                name,
                "",
                abbr,
                baseTypeId,
                true,
                id);
    }
}
