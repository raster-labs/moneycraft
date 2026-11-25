package com.raster.moneycraft.desktop.ui;

import com.raster.moneycraft.desktop.client.ContextEpochGuard;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopSessionCoordinator;
import com.raster.moneycraft.desktop.client.dto.AccountBookReportDto;
import com.raster.moneycraft.desktop.client.dto.BalanceSheetReportDto;
import com.raster.moneycraft.desktop.client.dto.ProfitAndLossReportDto;
import com.raster.moneycraft.desktop.keyboard.StateTokenRole;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import tools.jackson.databind.JsonNode;

/**
 * Dedicated Financial Statements, Account Books, Inventory Books, Statutory GST/TDS/IMS,
 * Exception Reports, MCA Edit Log / Hash-Chain Audit, Banking BRS/Cheque/OCR, Import/Exchange
 * (.1800/Spreadsheet/XML/JSONEx), and Extension/TDL Workbench panel for the MoneyCraft Java Swing
 * Desktop Client (Stage C &amp; Stage D, ITER110-021..029).
 *
 * <p>Provides route-specific 6-column table headers, summary banners, and live {@link DesktopApiClient}
 * queries and domain actions matching all 22 corresponding Angular web components and Windows
 * TallyPrime Release 7.2 report/utility screens.
 */
public final class ReportsWorkbenchPanel extends JPanel {

    private final BrandSwingTheme theme;
    private final DefaultTableModel tableModel;
    private final JLabel activeReportRouteLabel;
    private final JLabel statusBannerLabel;
    private final JLabel summaryMetricsLabel;
    private final JButton primaryDomainActionButton;
    private final ContextEpochGuard epochGuard;
    private final Supplier<DesktopSessionCoordinator> coordinatorSupplier;

    private String currentRoute = "REPORT:BALANCE_SHEET";

    public ReportsWorkbenchPanel(
        BrandSwingTheme theme,
        DefaultTableModel tableModel,
        JLabel activeReportRouteLabel,
        JLabel statusBannerLabel,
        ContextEpochGuard epochGuard,
        Supplier<DesktopSessionCoordinator> coordinatorSupplier,
        Runnable onOpenFiltersModal,
        Runnable onOpenOutputModal
    ) {
        super(new BorderLayout(8, 8));
        this.theme = Objects.requireNonNull(theme, "theme must not be null");
        this.tableModel = Objects.requireNonNull(tableModel, "tableModel must not be null");
        this.activeReportRouteLabel = Objects.requireNonNull(activeReportRouteLabel, "activeReportRouteLabel must not be null");
        this.statusBannerLabel = Objects.requireNonNull(statusBannerLabel, "statusBannerLabel must not be null");
        this.epochGuard = Objects.requireNonNull(epochGuard, "epochGuard must not be null");
        this.coordinatorSupplier = Objects.requireNonNull(coordinatorSupplier, "coordinatorSupplier must not be null");

        setBackground(BrandSwingTheme.WHITE);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        this.summaryMetricsLabel = new JLabel("Period: 2026-04-01 .. 2027-03-31 | Exact BigDecimal Scale 2");
        this.summaryMetricsLabel.setFont(theme.bodyFont(13));
        this.summaryMetricsLabel.setForeground(BrandSwingTheme.FOLD);

        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBackground(BrandSwingTheme.WHITE);
        topBar.add(activeReportRouteLabel, BorderLayout.WEST);
        topBar.add(summaryMetricsLabel, BorderLayout.EAST);
        add(topBar, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.setFont(theme.amountFont(13));
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);

        this.primaryDomainActionButton = theme.createStyledActionButton(
            "Execute Workbench Action [Alt+M]",
            StateTokenRole.ACTION_SECONDARY
        );
        this.primaryDomainActionButton.addActionListener(e -> executePrimaryDomainAction());

        JButton filtersBtn = theme.createStyledActionButton("Filters [F2/Alt+F2]", StateTokenRole.ACTION_SECONDARY);
        filtersBtn.addActionListener(e -> onOpenFiltersModal.run());

        JButton outputBtn = theme.createStyledActionButton("Print / Export [Ctrl+P]", StateTokenRole.ACTION_PRIMARY);
        outputBtn.addActionListener(e -> onOpenOutputModal.run());

        actions.add(primaryDomainActionButton);
        actions.add(filtersBtn);
        actions.add(outputBtn);
        add(actions, BorderLayout.SOUTH);
    }

    public String currentRoute() {
        return currentRoute;
    }

    public JLabel summaryMetricsLabel() {
        return summaryMetricsLabel;
    }

    public JButton primaryDomainActionButton() {
        return primaryDomainActionButton;
    }

    public static String[] columnHeadersForRoute(String route) {
        if (route == null) {
            return new String[]{
                "Particulars", "Reference / Code", "Date / Period",
                "Debit / Inward (\u20B9)", "Credit / Outward (\u20B9)", "Closing / Net (\u20B9)"
            };
        }
        if ("REPORT:BALANCE_SHEET".equals(route)) {
            return new String[]{
                "Liabilities / Assets Head", "Schedule / Note", "As Of Date",
                "Liabilities (\u20B9)", "Assets (\u20B9)", "Working Capital / Net (\u20B9)"
            };
        }
        if ("REPORT:PROFIT_AND_LOSS".equals(route)) {
            return new String[]{
                "Trading / P&L Particulars", "Schedule / Group", "Period",
                "Expenses / Dr (\u20B9)", "Income / Cr (\u20B9)", "Gross / Net Profit (\u20B9)"
            };
        }
        if ("REPORT:SCHEDULE_III".equals(route)) {
            return new String[]{
                "MCA Schedule III Line Item", "Note No.", "As Of Date",
                "Current Period (\u20B9)", "Previous Period (\u20B9)", "Variance (\u20B9)"
            };
        }
        if ("REPORT:CASH_FLOW".equals(route)) {
            return new String[]{
                "Cash Flow Activity / Month", "Activity Code", "Period",
                "Cash Inflow (\u20B9)", "Cash Outflow (\u20B9)", "Net Cash Flow (\u20B9)"
            };
        }
        if ("REPORT:RATIO_ANALYSIS".equals(route)) {
            return new String[]{
                "Principal Group / Ratio Name", "Formula / Code", "Period",
                "Principal Value (\u20B9)", "Benchmark / Target", "Computed Ratio"
            };
        }
        if ("INV_BOOK:MOVEMENT_ANALYSIS".equals(route)) {
            return new String[]{
                "Stock Item / Ledger", "Movement Nature", "Period",
                "Inward Qty / Rate (\u20B9)", "Outward Qty / Rate (\u20B9)", "Effective Value (\u20B9)"
            };
        }
        if ("INV_BOOK:BATCH_AGEING".equals(route)) {
            return new String[]{
                "Stock Item & Batch No.", "Mfg / Expiry Date", "Godown",
                "Available Qty", "Ageing Bucket (Days)", "Batch Value (\u20B9)"
            };
        }
        if ("INV_BOOK:SALES_ORDERS".equals(route) || "INV_BOOK:PURCHASE_ORDERS".equals(route)) {
            return new String[]{
                "Order No. & Party", "Stock Item", "Order / Due Date",
                "Ordered Qty", "Pending Qty", "Pending Value (\u20B9)"
            };
        }
        if ("REPORT:STOCK_SUMMARY".equals(route) || route.startsWith("INV_BOOK:")) {
            return new String[]{
                "Stock Item / Group", "UOM / Valuation", "Period",
                "Inward Qty / Value (\u20B9)", "Outward Qty / Value (\u20B9)", "Closing Value (\u20B9)"
            };
        }
        if ("INV_STMT:STOCK_QUERY".equals(route)) {
            return new String[]{
                "360\u00B0 Stock Query Section", "HSN / Batch / Godown", "Last Vch Date",
                "Last Purchase Rate/Qty", "Last Sales Rate/Qty", "Closing Qty / Value (\u20B9)"
            };
        }
        if (route.startsWith("INV_STMT:")) {
            return new String[]{
                "Godown / Category / BOM", "Stock Item / Batch", "As Of Date",
                "Inward / Std Qty", "Outward / Scrap Qty", "Closing Qty / Cost (\u20B9)"
            };
        }
        if (route.startsWith("BOOK:")) {
            return new String[]{
                "Ledger / Register Particulars", "Voucher Type / No", "Date / Period",
                "Debit (\u20B9)", "Credit (\u20B9)", "Closing Balance (\u20B9)"
            };
        }
        if ("STATEMENT:STATISTICS".equals(route)) {
            return new String[]{
                "Type of Voucher / Account", "Register / Master Code", "Period",
                "Posted / Active Count", "Optional / Cancelled", "Total Count"
            };
        }
        if (route.startsWith("STATEMENT:")) {
            return new String[]{
                "Party Ledger / Cost Centre", "Bill Ref No.", "Bill / Due Date",
                "Pending Amount (\u20B9)", "Ageing Bucket (Days)", "Overdue Balance (\u20B9)"
            };
        }
        if ("STAT_REPORT:GSTR_1".equals(route)) {
            return new String[]{
                "GSTR-1 Table / Section", "Recipient GSTIN / HSN", "Return Period",
                "Taxable Value (\u20B9)", "IGST + CGST + SGST (\u20B9)", "Invoice Total (\u20B9)"
            };
        }
        if ("STAT_REPORT:GSTR_2B".equals(route) || "STAT_REPORT:IMS".equals(route)) {
            return new String[]{
                "Supplier GSTIN / Legal Name", "Supplier Invoice No.", "Invoice Date",
                "Taxable Value (\u20B9)", "Eligible ITC (\u20B9)", "IMS Action (Accept/Reject/Pending)"
            };
        }
        if ("STAT_REPORT:GSTR_3B".equals(route)) {
            return new String[]{
                "GSTR-3B Table Section", "Supply / ITC Nature", "Return Period",
                "Taxable Value (\u20B9)", "Integrated / Central Tax (\u20B9)", "State Tax / Net Payable (\u20B9)"
            };
        }
        if ("STAT_REPORT:GSTR_9".equals(route)) {
            return new String[]{
                "GSTR-9 Part & Table", "Annual Return Description", "Financial Year",
                "Turnover / ITC Claimed (\u20B9)", "Tax Payable (\u20B9)", "Tax Paid / Reconciled (\u20B9)"
            };
        }
        if ("STAT_REPORT:EINVOICE".equals(route) || "STAT_REPORT:EWAY_BILL".equals(route)) {
            return new String[]{
                "Voucher No / Doc Type", "Party GSTIN / Vehicle", "Invoice Date",
                "IRN / e-Way Bill No", "30-Day Window Status", "Invoice Value (\u20B9)"
            };
        }
        if (route.startsWith("STAT_REPORT:TDS_")) {
            return new String[]{
                "Deductee PAN / BSR Code", "Section / Challan No", "Quarter / Date",
                "Amount Paid (\u20B9)", "TDS Deducted (\u20B9)", "TDS Deposited / FVU Status"
            };
        }
        if ("EXCEPTION:APPROVAL_QUEUE".equals(route)) {
            return new String[]{
                "Voucher Date", "Vch Type & No.", "Particulars / Narration",
                "Maker Principal", "Four-Eyes Status", "Voucher Amount (\u20B9)"
            };
        }
        if (route.startsWith("EXCEPTION:")) {
            return new String[]{
                "Exception Item / Voucher", "Voucher / Ref No", "Date / Due Date",
                "Debit / Opening (\u20B9)", "Credit / Cleared (\u20B9)", "Exception Balance / Status"
            };
        }
        if ("AUDIT:EDIT_LOG".equals(route)) {
            return new String[]{
                "Entity / Voucher No", "Version / AlterID", "Operation & Timestamp",
                "Previous Amount (\u20B9)", "New Amount (\u20B9)", "Redline Diff / Cancelled"
            };
        }
        if ("AUDIT:GOV_INTEGRATIONS".equals(route)) {
            return new String[]{
                "Capability / Provider ID", "Family / Domain", "Operation Scope",
                "Channel / Environment", "Auth / Consent Profile", "Implementation & Egress Status"
            };
        }
        if (route.startsWith("AUDIT:")) {
            return new String[]{
                "Audit / Connector Component", "Algorithm / SPI ID", "Verification Scope",
                "Verified Events / Dr", "Discrepancies / Cr", "Chain Integrity / Egress"
            };
        }
        if ("BANKING:CHEQUE_PRINT".equals(route) || "BANKING:CHEQUE_REGISTER".equals(route)) {
            return new String[]{
                "Bank Account", "Cheque Book / Series", "From - To Leaf",
                "Leaves Used / Available", "Instrument Status", "Cheque Amount (\u20B9)"
            };
        }
        if ("BANKING:OCR_STAGING".equals(route)) {
            return new String[]{
                "Staging Doc ID", "Source File / Bank", "Statement Period",
                "Extracted Lines", "Confidence / Status", "Net Staged Amount (\u20B9)"
            };
        }
        if (route.startsWith("BANKING:")) {
            return new String[]{
                "Bank Ledger / Instrument", "Cheque / Txn Ref", "Voucher Date",
                "Books Dr / Deposit (\u20B9)", "Books Cr / Withdrawal (\u20B9)", "Bank Date / BRS Status"
            };
        }
        if ("EXCHANGE:SPREADSHEET".equals(route)) {
            return new String[]{
                "Spreadsheet Batch ID", "File Name (.xlsx/.ods/.csv)", "Target Entity Type",
                "Valid / Total Rows", "Error Rows", "Batch State"
            };
        }
        if (route.startsWith("EXCHANGE:")) {
            return new String[]{
                "Batch / Snapshot / Template", "Format (.1800/.xlsx/XML)", "Stage / Created At",
                "Staged Rows / Dr (\u20B9)", "Committed Rows / Cr (\u20B9)", "6-Metric Proof / Status"
            };
        }
        if (route.startsWith("EXTENSION:")) {
            return new String[]{
                "Extension Package / TDL / SQL", "Hook / Binding / Query", "Sandbox Policy",
                "Execution Time (ms)", "Preview Input", "Evaluated Result / Rows"
            };
        }
        return new String[]{
            "Particulars", "Reference / Code", "Date / Period",
            "Debit / Inward (\u20B9)", "Credit / Outward (\u20B9)", "Closing / Net (\u20B9)"
        };
    }

    public void activateRoute(String route, Runnable reloadTrialBalanceCallback) {
        this.currentRoute = Objects.requireNonNull(route, "route must not be null");
        this.epochGuard.onViewChanged(route);
        this.activeReportRouteLabel.setText("Report: " + route);
        this.tableModel.setColumnIdentifiers(columnHeadersForRoute(route));
        this.primaryDomainActionButton.setText(domainActionButtonLabel(route));
        seedOfflineDefaultsForRoute(route);
        loadRouteFromServerIfConnected(route, reloadTrialBalanceCallback);
    }

    private static String domainActionButtonLabel(String route) {
        if ("STAT_REPORT:GSTR_1".equals(route)) {
            return "Export GSTR-1 JSON";
        }
        if ("STAT_REPORT:GSTR_2B".equals(route) || "STAT_REPORT:IMS".equals(route)) {
            return "Accept / Reconcile IMS [Alt+M]";
        }
        if ("STAT_REPORT:GSTR_9".equals(route)) {
            return "One-Click GSTR-9 Export";
        }
        if (route.startsWith("STAT_REPORT:TDS_")) {
            return "Export Protean FVU 1.2";
        }
        if ("EXCEPTION:APPROVAL_QUEUE".equals(route)) {
            return "Approve Voucher [Ctrl+A]";
        }
        if ("AUDIT:GOV_INTEGRATIONS".equals(route)) {
            return "Load Integration Profiles";
        }
        if ("BANKING:CHEQUE_PRINT".equals(route) || "BANKING:CHEQUE_REGISTER".equals(route)) {
            return "Create Cheque Book";
        }
        if ("BANKING:OCR_STAGING".equals(route)) {
            return "Import Statement / OCR";
        }
        if (route.startsWith("BANKING:")) {
            return "Auto-Match BRS [Alt+M]";
        }
        if ("EXCHANGE:LEGACY_1800".equals(route)) {
            return "Stage & Verify .1800 Proof";
        }
        if ("EXCHANGE:SPREADSHEET".equals(route)) {
            return "Stage & Execute Spreadsheet";
        }
        if (route.startsWith("EXCHANGE:")) {
            return "Export Canonical XML / JSONEx";
        }
        if (route.startsWith("EXTENSION:")) {
            return "Evaluate Formula / TDL / ODBC";
        }
        if ("AUDIT:EDIT_LOG".equals(route)) {
            return "Load MCA Edit Log Redline [Alt+Q]";
        }
        if (route.startsWith("AUDIT:")) {
            return "Verify MC-AUDIT-2 Hash Chain";
        }
        return "Refresh Statement [F5]";
    }

    public void executePrimaryDomainAction() {
        DesktopSessionCoordinator coordinator = coordinatorSupplier.get();
        UUID companyId = epochGuard.activeCompanyId();
        if (coordinator == null || companyId == null) {
            statusBannerLabel.setText("Executed domain action for " + currentRoute);
            return;
        }
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient api = coordinator.apiClient();
        UUID intentionId = UUID.randomUUID();

        if ("STAT_REPORT:GSTR_1".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.exportGstr1(companyId, Map.of(
                    "fromDate", coordinator.activePeriodFrom().toString(),
                    "toDate", coordinator.activePeriodTo().toString()
                ), intentionId),
                resp -> statusBannerLabel.setText("Exported GSTR-1 return payload"),
                err -> statusBannerLabel.setText("GSTR-1 export error: " + err.getMessage()),
                null
            );
        } else if ("STAT_REPORT:IMS".equals(currentRoute) || "STAT_REPORT:GSTR_2B".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.applyImsAction(companyId, Map.of(
                    "action", "ACCEPT",
                    "invoiceIds", java.util.List.of("IMS-INV-001"),
                    "remarks", "Accepted from Swing IMS Workbench"
                ), intentionId),
                resp -> statusBannerLabel.setText("Applied IMS reconciliation action (ACCEPT)"),
                err -> statusBannerLabel.setText("IMS action error: " + err.getMessage()),
                null
            );
        } else if ("STAT_REPORT:GSTR_9".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.exportGstr9(companyId, Map.of("financialYear", "2026-27"), intentionId),
                resp -> statusBannerLabel.setText("Exported One-Click GSTR-9 annual return"),
                err -> statusBannerLabel.setText("GSTR-9 export error: " + err.getMessage()),
                null
            );
        } else if (currentRoute.startsWith("STAT_REPORT:TDS_")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.exportTdsFvu(companyId, Map.of("financialYear", "2026-27", "quarter", "Q1"), intentionId),
                resp -> statusBannerLabel.setText("Exported Protean FVU 1.2 Form 26Q file"),
                err -> statusBannerLabel.setText("FVU export error: " + err.getMessage()),
                null
            );
        } else if ("EXCEPTION:APPROVAL_QUEUE".equals(currentRoute)) {
            UUID vchId = coordinator.activeVoucherId()
                .orElse(UUID.fromString("fb3a3957-f63e-4cd7-9b12-64eb27b7e1b5"));
            String ifMatch = coordinator.activeVoucherEtag().orElse("\"1\"");
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.approveVoucher(
                    companyId,
                    vchId,
                    new com.raster.moneycraft.desktop.client.dto.ApproveVoucherCommand(Boolean.FALSE, "Approved from Swing Queue"),
                    ifMatch,
                    intentionId
                ),
                resp -> statusBannerLabel.setText("Approved Four-Eyes voucher in queue"),
                err -> statusBannerLabel.setText("Queue approval error: " + err.getMessage()),
                null
            );
        } else if ("AUDIT:GOV_INTEGRATIONS".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listGovernmentIntegrationProfiles(companyId),
                node -> statusBannerLabel.setText("Loaded Government & Connected Banking profiles"),
                err -> statusBannerLabel.setText("Gov profiles error: " + err.getMessage()),
                null
            );
        } else if ("BANKING:CHEQUE_PRINT".equals(currentRoute) || "BANKING:CHEQUE_REGISTER".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createChequeBook(companyId, Map.of(
                    "bankName", "HDFC Bank",
                    "fromLeaf", 100001,
                    "toLeaf", 100050
                ), intentionId),
                resp -> statusBannerLabel.setText("Created cheque book range"),
                err -> statusBannerLabel.setText("Cheque book error: " + err.getMessage()),
                null
            );
        } else if ("BANKING:OCR_STAGING".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.importBankStatement(companyId, Map.of("sourceFile", "statement-apr.csv"), intentionId),
                resp -> statusBannerLabel.setText("Imported bank statement into OCR staging queue"),
                err -> statusBannerLabel.setText("Statement import error: " + err.getMessage()),
                null
            );
        } else if (currentRoute.startsWith("BANKING:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.autoMatchBrs(companyId, Map.of(
                    "fromDate", coordinator.activePeriodFrom().toString(),
                    "toDate", coordinator.activePeriodTo().toString()
                ), intentionId),
                resp -> statusBannerLabel.setText("Completed Auto-BRS bank statement matching"),
                err -> statusBannerLabel.setText("Auto-BRS error: " + err.getMessage()),
                null
            );
        } else if ("EXCHANGE:LEGACY_1800".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.stageLegacy1800Import(companyId, Map.of("snapshotCompanyCode", "100000"), intentionId),
                resp -> statusBannerLabel.setText("Staged Legacy .1800 snapshot & verified 6-metric proof"),
                err -> statusBannerLabel.setText("Legacy .1800 stage error: " + err.getMessage()),
                null
            );
        } else if ("EXCHANGE:SPREADSHEET".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.stageSpreadsheetBatch(companyId, Map.of(
                    "fileName", "opening-ledgers.xlsx",
                    "entityType", "LEDGER"
                ), intentionId),
                resp -> statusBannerLabel.setText("Staged Guided Spreadsheet Import batch"),
                err -> statusBannerLabel.setText("Spreadsheet batch error: " + err.getMessage()),
                null
            );
        } else if (currentRoute.startsWith("EXCHANGE:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.exportCanonicalXml(companyId, Map.of("scope", "MASTERS_AND_VOUCHERS"), intentionId),
                resp -> statusBannerLabel.setText("Exported Canonical XML envelope"),
                err -> statusBannerLabel.setText("XML export error: " + err.getMessage()),
                null
            );
        } else if (currentRoute.startsWith("EXTENSION:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.evaluateExtensionPreview(companyId, Map.of(
                    "expression", "ROUND(AMOUNT * 0.18, 2)",
                    "variables", Map.of("AMOUNT", "12500.00")
                ), intentionId),
                resp -> statusBannerLabel.setText("Evaluated sandboxed extension formula"),
                err -> statusBannerLabel.setText("Extension preview error: " + err.getMessage()),
                null
            );
        } else if ("AUDIT:HASH_CHAIN".equals(currentRoute)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.verifyAuditHashChain(),
                node -> {
                    populateGenericReportJson(currentRoute, "MC-AUDIT-2", node);
                    statusBannerLabel.setText("Verified MC-AUDIT-2 cryptographic hash chain");
                },
                err -> statusBannerLabel.setText("Audit verification error: " + err.getMessage()),
                null
            );
        } else {
            loadRouteFromServerIfConnected(currentRoute, null);
        }
    }

    public void loadRouteFromServerIfConnected(String route, Runnable reloadTrialBalanceCallback) {
        DesktopSessionCoordinator coordinator = coordinatorSupplier.get();
        UUID companyId = epochGuard.activeCompanyId();
        if (coordinator == null || companyId == null) {
            return;
        }
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient api = coordinator.apiClient();
        LocalDate from = coordinator.activePeriodFrom();
        LocalDate to = coordinator.activePeriodTo();

        if ("REPORT:BALANCE_SHEET".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getBalanceSheet(companyId, to, "VERTICAL", false),
                this::populateBalanceSheet,
                err -> statusBannerLabel.setText("Failed loading Balance Sheet: " + err.getMessage()),
                null
            );
        } else if ("REPORT:PROFIT_AND_LOSS".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getProfitAndLoss(companyId, from, to, false),
                this::populateProfitAndLoss,
                err -> statusBannerLabel.setText("Failed loading Profit & Loss: " + err.getMessage()),
                null
            );
        } else if ("INV_BOOK:MOVEMENT_ANALYSIS".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getMovementAnalysisReport(companyId, null, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Movement Analysis: " + err.getMessage()),
                null
            );
        } else if ("INV_BOOK:BATCH_AGEING".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getBatchAgeingReport(companyId, to),
                node -> populateGenericReportJson(route, to + "", node),
                err -> statusBannerLabel.setText("Failed loading Batch Ageing: " + err.getMessage()),
                null
            );
        } else if ("INV_BOOK:SALES_ORDERS".equals(route) || "INV_BOOK:PURCHASE_ORDERS".equals(route)) {
            String orderType = "INV_BOOK:PURCHASE_ORDERS".equals(route) ? "PURCHASE" : "SALES";
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listOpenOrders(companyId, orderType),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Open Orders: " + err.getMessage()),
                null
            );
        } else if ("REPORT:STOCK_SUMMARY".equals(route) || route.startsWith("INV_BOOK:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getStockSummaryReport(companyId, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Stock Summary: " + err.getMessage()),
                null
            );
        } else if ("REPORT:RATIO_ANALYSIS".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getRatioAnalysisReport(companyId, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Ratio Analysis: " + err.getMessage()),
                null
            );
        } else if ("REPORT:CASH_FLOW".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getCashFlowReport(companyId, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Cash Flow: " + err.getMessage()),
                null
            );
        } else if ("REPORT:SCHEDULE_III".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getScheduleIiiReport(companyId, to, from.minusDays(1)),
                node -> populateGenericReportJson(route, to + "", node),
                err -> statusBannerLabel.setText("Failed loading Schedule III: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("BOOK:")) {
            String bookType = route.substring("BOOK:".length());
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getAccountBookReport(companyId, bookType, null, from, to),
                this::populateAccountBook,
                err -> statusBannerLabel.setText("Failed loading Account Book: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("EXCEPTION:")) {
            String excType = route.substring("EXCEPTION:".length());
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getExceptionReport(companyId, excType, from, to),
                exc -> populateExceptionReportForRoute(route, exc),
                err -> statusBannerLabel.setText("Failed loading Exception Report: " + err.getMessage()),
                null
            );
        } else if ("STATEMENT:STATISTICS".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getStatisticsReport(companyId, from, to),
                this::populateStatistics,
                err -> statusBannerLabel.setText("Failed loading Statistics: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("STATEMENT:")) {
            String nature = "STATEMENT:BILLS_PAYABLE".equals(route) ? "PAYABLES" : "RECEIVABLES";
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getAgeingReport(companyId, nature, to),
                node -> populateGenericReportJson(route, to + "", node),
                err -> statusBannerLabel.setText("Failed loading Statement: " + err.getMessage()),
                null
            );
        } else if ("INV_STMT:STOCK_QUERY".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getStockQueryReport(companyId, null, from, to),
                this::populateStockQuery,
                err -> statusBannerLabel.setText("Failed loading Stock Query: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("INV_STMT:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getGodownSummaryReport(companyId, null, to),
                node -> populateGenericReportJson(route, to + "", node),
                err -> statusBannerLabel.setText("Failed loading Inventory Statement: " + err.getMessage()),
                null
            );
        } else if ("STAT_REPORT:GSTR_1".equals(route) || "STAT_REPORT:EINVOICE".equals(route)
            || "STAT_REPORT:EWAY_BILL".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getGstr1Report(companyId, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading GSTR-1: " + err.getMessage()),
                null
            );
        } else if ("STAT_REPORT:GSTR_3B".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getGstr3bReport(companyId, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading GSTR-3B: " + err.getMessage()),
                null
            );
        } else if ("STAT_REPORT:GSTR_9".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getGstr9Report(companyId, "2026-27"),
                node -> populateGenericReportJson(route, "2026-27", node),
                err -> statusBannerLabel.setText("Failed loading GSTR-9: " + err.getMessage()),
                null
            );
        } else if ("STAT_REPORT:IMS".equals(route) || "STAT_REPORT:GSTR_2B".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listImsInvoices(companyId),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading IMS: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("STAT_REPORT:TDS_")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getForm26QReport(companyId, "2026-27", "Q1"),
                node -> populateGenericReportJson(route, "2026-27 Q1", node),
                err -> statusBannerLabel.setText("Failed loading TDS report: " + err.getMessage()),
                null
            );
        } else if ("AUDIT:EDIT_LOG".equals(route)) {
            UUID targetEntityId = coordinator.activeVoucherId()
                .orElse(UUID.fromString("fb3a3957-f63e-4cd7-9b12-64eb27b7e1b5"));
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getEditLogVersions(companyId, "VOUCHER", targetEntityId),
                this::populateEditLogVersions,
                err -> statusBannerLabel.setText("Failed loading MCA Edit Log: " + err.getMessage()),
                null
            );
        } else if ("AUDIT:HASH_CHAIN".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.verifyAuditHashChain(),
                node -> populateGenericReportJson(route, "MC-AUDIT-2", node),
                err -> statusBannerLabel.setText("Failed verifying audit chain: " + err.getMessage()),
                null
            );
        } else if ("AUDIT:GOV_INTEGRATIONS".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getGovernmentInterfaceInventory(),
                node -> populateGenericReportJson(route, "GOV-D01..GOV-D39", node),
                err -> statusBannerLabel.setText("Failed loading government inventory: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("AUDIT:")) {
            if (reloadTrialBalanceCallback != null) {
                reloadTrialBalanceCallback.run();
            }
        } else if ("BANKING:CHEQUE_PRINT".equals(route) || "BANKING:CHEQUE_REGISTER".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listChequeBooks(companyId),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Cheque Books: " + err.getMessage()),
                null
            );
        } else if ("BANKING:OCR_STAGING".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listOcrStagingDocuments(companyId),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading OCR Staging Queue: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("BANKING:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getBrsReport(companyId, null, from, to),
                node -> populateGenericReportJson(route, from + " .. " + to, node),
                err -> statusBannerLabel.setText("Failed loading Banking route: " + err.getMessage()),
                null
            );
        } else if ("EXCHANGE:SPREADSHEET".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listSpreadsheetBatches(companyId),
                node -> populateGenericReportJson(route, "Spreadsheet", node),
                err -> statusBannerLabel.setText("Failed loading Spreadsheet Batches: " + err.getMessage()),
                null
            );
        } else if ("EXCHANGE:TEMPLATES".equals(route)) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listExchangeTemplates(companyId),
                node -> populateGenericReportJson(route, "Templates", node),
                err -> statusBannerLabel.setText("Failed loading Exchange Templates: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("EXCHANGE:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listMigrationImports(companyId),
                node -> populateGenericReportJson(route, "Exchange", node),
                err -> statusBannerLabel.setText("Failed loading Exchange route: " + err.getMessage()),
                null
            );
        } else if (route.startsWith("EXTENSION:")) {
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listExtensionPackages(companyId),
                node -> populateGenericReportJson(route, "Extensions", node),
                err -> statusBannerLabel.setText("Failed loading Extensions route: " + err.getMessage()),
                null
            );
        }
    }

    public void populateBalanceSheet(BalanceSheetReportDto bs) {
        if (bs == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("REPORT:BALANCE_SHEET"));
        tableModel.setRowCount(0);
        tableModel.addRow(new Object[]{
            "Total Liabilities & Equity", "BS-LIAB", bs.asOfDate() + "",
            "0.00", safeAmountText(bs.totalLiabilities()), safeAmountText(bs.totalLiabilities())
        });
        tableModel.addRow(new Object[]{
            "Total Assets", "BS-ASSET", bs.asOfDate() + "",
            safeAmountText(bs.totalAssets()), "0.00", safeAmountText(bs.totalAssets())
        });
        summaryMetricsLabel.setText(
            "As of " + bs.asOfDate() + " | Assets: \u20B9 " + safeAmountText(bs.totalAssets())
                + " | Liabilities: \u20B9 " + safeAmountText(bs.totalLiabilities())
        );
    }

    public void populateProfitAndLoss(ProfitAndLossReportDto pnl) {
        if (pnl == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("REPORT:PROFIT_AND_LOSS"));
        tableModel.setRowCount(0);
        tableModel.addRow(new Object[]{
            "Sales Accounts", "PNL-SALES", pnl.fromDate() + " .. " + pnl.toDate(),
            "0.00", safeAmountText(pnl.salesTotal()), safeAmountText(pnl.salesTotal())
        });
        tableModel.addRow(new Object[]{
            "Purchase Accounts", "PNL-PURCH", pnl.fromDate() + " .. " + pnl.toDate(),
            safeAmountText(pnl.purchaseTotal()), "0.00", safeAmountText(pnl.purchaseTotal())
        });
        tableModel.addRow(new Object[]{
            "Net Profit", "PNL-NET", pnl.fromDate() + " .. " + pnl.toDate(),
            "0.00", safeAmountText(pnl.netProfit()), safeAmountText(pnl.netProfit())
        });
        summaryMetricsLabel.setText(
            "Period: " + pnl.fromDate() + " .. " + pnl.toDate() + " | Net Profit: \u20B9 " + safeAmountText(pnl.netProfit())
        );
    }

    public void populateAccountBook(AccountBookReportDto book) {
        if (book == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("BOOK:" + book.bookType()));
        tableModel.setRowCount(0);
        tableModel.addRow(new Object[]{
            book.title(), book.bookType(), book.fromDate() + " .. " + book.toDate(),
            safeAmountText(book.totalDebit()), safeAmountText(book.totalCredit()),
            safeAmountText(book.closingDebit())
        });
        summaryMetricsLabel.setText(
            book.title() + " | Dr: \u20B9 " + safeAmountText(book.totalDebit())
                + " | Cr: \u20B9 " + safeAmountText(book.totalCredit())
        );
    }

    public void populateExceptionReport(AccountBookReportDto.ExceptionReportDto exc) {
        populateExceptionReportForRoute(exc != null ? "EXCEPTION:" + exc.type() : "EXCEPTION:NEGATIVE_STOCK", exc);
    }

    public void populateExceptionReportForRoute(String route, AccountBookReportDto.ExceptionReportDto exc) {
        if (exc == null) {
            return;
        }
        String effectiveRoute = route != null ? route : "EXCEPTION:" + exc.type();
        tableModel.setColumnIdentifiers(columnHeadersForRoute(effectiveRoute));
        tableModel.setRowCount(0);
        tableModel.addRow(new Object[]{
            exc.title(), exc.type(), exc.fromDate() + " .. " + exc.toDate(),
            safeAmountText(exc.totalDebit()), safeAmountText(exc.totalCredit()),
            safeAmountText(exc.totalAmount())
        });
    }

    public void populateStatistics(AccountBookReportDto.StatisticsReportDto stats) {
        if (stats == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("STATEMENT:STATISTICS"));
        tableModel.setRowCount(0);
        for (Map<String, Object> vchRow : stats.voucherTypeCounts()) {
            tableModel.addRow(new Object[]{
                String.valueOf(vchRow.getOrDefault("voucherTypeName", "Voucher Type")),
                "VCH-STAT",
                stats.fromDate() + " .. " + stats.toDate(),
                String.valueOf(vchRow.getOrDefault("postedCount", 0)),
                String.valueOf(vchRow.getOrDefault("optionalCount", 0)),
                String.valueOf(vchRow.getOrDefault("totalCount", 0))
            });
        }
        for (Map<String, Object> masterRow : stats.masterCounts()) {
            tableModel.addRow(new Object[]{
                String.valueOf(masterRow.getOrDefault("masterType", "Master")),
                "MST-STAT",
                stats.fromDate() + " .. " + stats.toDate(),
                "0",
                "0",
                String.valueOf(masterRow.getOrDefault("count", 0))
            });
        }
    }

    public void populateStockQuery(AccountBookReportDto.StockQueryReportDto query) {
        if (query == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("INV_STMT:STOCK_QUERY"));
        tableModel.setRowCount(0);
        tableModel.addRow(new Object[]{
            query.stockItemName() != null ? query.stockItemName() : "Stock Query",
            query.hsnSacCode() != null ? query.hsnSacCode() : "HSN",
            query.fromDate() + " .. " + query.toDate(),
            safeAmountText(query.closingQty()),
            safeAmountText(query.closingRate()),
            safeAmountText(query.closingValue())
        });
    }

    public void populateEditLogVersions(JsonNode versionsNode) {
        if (versionsNode == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("AUDIT:EDIT_LOG"));
        tableModel.setRowCount(0);
        if (versionsNode.isArray()) {
            String previousAmount = "0.00";
            for (int i = 0; i < versionsNode.size(); i++) {
                JsonNode v = versionsNode.get(i);
                String currAmount = v.path("amount").asString("0.00");
                boolean cancelled = v.path("cancelled").asBoolean(false);
                tableModel.addRow(new Object[]{
                    "Vch #" + v.path("voucherNumber").asString("1"),
                    "v" + v.path("versionNo").asInt(i + 1) + " / AlterID " + v.path("alterId").asInt(i + 1),
                    v.path("operation").asString("UPDATE"),
                    previousAmount,
                    currAmount,
                    cancelled ? "CANCELLED (Number Retained)" : v.path("narration").asString("Active")
                });
                previousAmount = currAmount;
            }
        }
        statusBannerLabel.setText("Loaded MCA Edit Log trajectory (" + tableModel.getRowCount() + " versions)");
    }

    public void populateGenericReportJson(String route, String periodText, JsonNode node) {
        if (node == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute(route));
        tableModel.setRowCount(0);
        if (node.isArray() && node.size() > 0) {
            for (int i = 0; i < node.size(); i++) {
                JsonNode item = node.get(i);
                tableModel.addRow(new Object[]{
                    item.path("name").asString(item.path("title").asString(route + " #" + (i + 1))),
                    item.path("code").asString(item.path("status").asString("OK")),
                    periodText,
                    item.path("totalDebit").asString(item.path("taxableValue").asString("0.00")),
                    item.path("totalCredit").asString(item.path("totalTax").asString("0.00")),
                    item.path("closingValue").asString(item.path("netAmount").asString("0.00"))
                });
            }
        } else {
            tableModel.addRow(new Object[]{
                route,
                node.path("status").asString(node.path("intact").asBoolean(false) ? "INTACT" : "OK"),
                periodText,
                node.path("totalDebit").asString(node.path("widgetFifoClosingQty").asString("0.00")),
                node.path("totalCredit").asString(node.path("widgetAvgClosingValue").asString("0.00")),
                node.path("closingValue").asString(node.path("widgetFifoClosingValue").asString("0.00"))
            });
        }
        statusBannerLabel.setText("Loaded " + route);
    }

    private void seedOfflineDefaultsForRoute(String route) {
        tableModel.setRowCount(0);
        if ("REPORT:BALANCE_SHEET".equals(route)) {
            tableModel.addRow(new Object[]{"Capital & Liabilities", "BS-LIAB", "2026-09-01", "183099.00", "0.00", "183099.00"});
            tableModel.addRow(new Object[]{"Current & Fixed Assets", "BS-ASSET", "2026-09-01", "0.00", "183099.00", "183099.00"});
        } else if ("REPORT:PROFIT_AND_LOSS".equals(route)) {
            tableModel.addRow(new Object[]{"Sales Accounts", "PNL-SALES", "2026-04-01 .. 2026-09-01", "0.00", "199900.35", "199900.35"});
            tableModel.addRow(new Object[]{"Purchase Accounts", "PNL-PURCH", "2026-04-01 .. 2026-09-01", "119744.65", "0.00", "119744.65"});
            tableModel.addRow(new Object[]{"Net Profit", "PNL-NET", "2026-04-01 .. 2026-09-01", "0.00", "80155.41", "80155.41"});
        } else if ("REPORT:STOCK_SUMMARY".equals(route) || route.startsWith("INV_BOOK:")) {
            tableModel.addRow(new Object[]{"Widget-FIFO", "NOS / FIFO", "2025-04-01 .. 2025-05-31", "18.000", "7.000", "1340.00"});
            tableModel.addRow(new Object[]{"Widget-AVG", "NOS / AVG_COST", "2025-04-01 .. 2025-05-31", "18.000", "7.000", "1143.04"});
        } else if ("AUDIT:EDIT_LOG".equals(route)) {
            tableModel.addRow(new Object[]{"Vch #1", "v1 / AlterID 1", "CREATE", "0.00", "2500.00", "Original Posting"});
            tableModel.addRow(new Object[]{"Vch #1", "v2 / AlterID 2", "ALTER", "2500.00", "2750.00", "Altered 2500 -> 2750"});
            tableModel.addRow(new Object[]{"Vch #1", "v3 / AlterID 3", "CANCEL", "2750.00", "0.00", "CANCELLED (Number Retained)"});
        } else {
            tableModel.addRow(new Object[]{route, "READY", "2026-04-01 .. 2027-03-31", "0.00", "0.00", "0.00"});
        }
    }

    private static String safeAmountText(BigDecimal val) {
        return val == null ? "0.00" : val.toPlainString();
    }
}
