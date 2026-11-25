package com.raster.moneycraft.desktop.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Complete Level 1 -> Level 2 -> Level 3 cascading menu model for Gateway of MoneyCraft (UM-110-01).
 * Supports single-letter mnemonic selection, Up/Down/Enter drill-down in place, live breadcrumb
 * path formatting, and Escape step-back (Level 3 -> Level 2 -> Level 1).
 */
public final class GatewayCascadeTree {

    private final List<MenuNode> rootItems;
    private final Deque<MenuLevelFrame> stack = new ArrayDeque<>();
    private int selectedIndex;

    public GatewayCascadeTree() {
        this.rootItems = buildGatewayRootItems();
        resetToRoot();
    }

    public void resetToRoot() {
        stack.clear();
        stack.push(new MenuLevelFrame("Gateway", rootItems, 0));
        this.selectedIndex = 0;
    }

    public int currentLevelDepth() {
        return stack.size();
    }

    public List<MenuNode> currentItems() {
        return stack.isEmpty() ? rootItems : stack.peek().items();
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void moveSelection(int delta) {
        List<MenuNode> items = currentItems();
        if (items.isEmpty()) {
            return;
        }
        int size = items.size();
        this.selectedIndex = ((selectedIndex + delta) % size + size) % size;
    }

    public void setSelectedIndex(int idx) {
        List<MenuNode> items = currentItems();
        if (idx >= 0 && idx < items.size()) {
            this.selectedIndex = idx;
        }
    }

    public String breadcrumbPath() {
        List<MenuLevelFrame> ordered = new ArrayList<>(stack);
        Collections.reverse(ordered);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ordered.size(); i++) {
            if (i > 0) {
                sb.append(" > ");
            }
            sb.append(ordered.get(i).title());
        }
        return sb.toString();
    }

    /**
     * Activates the currently highlighted menu item: drills in-place if it owns child items,
     * or returns the leaf target workbench/report route identifier.
     */
    public DrillOutcome activateSelected() {
        List<MenuNode> items = currentItems();
        if (items.isEmpty()) {
            return new DrillOutcome(false, null, breadcrumbPath());
        }
        return activateNode(items.get(selectedIndex), selectedIndex);
    }

    /**
     * Activates an item at the current menu level by its case-insensitive single-letter mnemonic.
     */
    public DrillOutcome activateByMnemonic(char ch) {
        char upper = Character.toUpperCase(ch);
        List<MenuNode> items = currentItems();
        for (int i = 0; i < items.size(); i++) {
            MenuNode node = items.get(i);
            if (Character.toUpperCase(node.mnemonic()) == upper) {
                this.selectedIndex = i;
                return activateNode(node, i);
            }
        }
        return new DrillOutcome(false, null, breadcrumbPath());
    }

    /**
     * Steps back one level on Escape (Level 3 -> Level 2 -> Level 1).
     *
     * @return true if a child menu level was popped; false if already at Level 1 root
     */
    public boolean stepBackOnEscape() {
        if (stack.size() <= 1) {
            return false;
        }
        MenuLevelFrame popped = stack.pop();
        this.selectedIndex = Math.min(popped.parentSelectedIndex(), currentItems().size() - 1);
        return true;
    }

    private DrillOutcome activateNode(MenuNode node, int parentIndex) {
        if (!node.children().isEmpty()) {
            stack.push(new MenuLevelFrame(node.label(), node.children(), parentIndex));
            this.selectedIndex = 0;
            return new DrillOutcome(true, null, breadcrumbPath());
        }
        return new DrillOutcome(false, node.targetActionId(), breadcrumbPath() + " > " + node.label());
    }

    private static List<MenuNode> buildGatewayRootItems() {
        List<MenuNode> createAndAlterChildren = List.of(
            MenuNode.branch('A', "Accounting Masters", List.of(
                MenuNode.leaf('G', "Group", "MASTER:GROUP"),
                MenuNode.leaf('L', "Ledger", "MASTER:LEDGER"),
                MenuNode.leaf('C', "Currency", "MASTER:CURRENCY"),
                MenuNode.leaf('F', "Forex Rates", "MASTER:FOREX_RATES"),
                MenuNode.leaf('O', "Cost Category", "MASTER:COST_CATEGORY"),
                MenuNode.leaf('E', "Cost Centre", "MASTER:COST_CENTRE"),
                MenuNode.leaf('V', "Voucher Type", "MASTER:VOUCHER_TYPE")
            )),
            MenuNode.branch('I', "Inventory Masters", List.of(
                MenuNode.leaf('G', "Stock Group", "MASTER:STOCK_GROUP"),
                MenuNode.leaf('C', "Stock Category", "MASTER:STOCK_CATEGORY"),
                MenuNode.leaf('I', "Stock Item", "MASTER:STOCK_ITEM"),
                MenuNode.leaf('U', "Unit (UOM)", "MASTER:UOM"),
                MenuNode.leaf('L', "Godown / Location", "MASTER:GODOWN"),
                MenuNode.leaf('P', "Price Levels & Price List", "MASTER:PRICE_LIST"),
                MenuNode.leaf('B', "Bill of Materials (BOM)", "MASTER:BOM")
            )),
            MenuNode.branch('S', "Statutory Masters & Details", List.of(
                MenuNode.leaf('G', "GST Registrations (Multi-GSTIN)", "STATUTORY:GST_REGISTRATIONS"),
                MenuNode.leaf('D', "Company GST Details", "STATUTORY:GST_DETAILS"),
                MenuNode.leaf('T', "TDS Nature of Payment", "STATUTORY:TDS_NATURE"),
                MenuNode.leaf('P', "Company PAN / CIN Details", "STATUTORY:COMPANY_PAN_CIN")
            ))
        );

        List<MenuNode> displayMoreChildren = List.of(
            MenuNode.leaf('T', "Trial Balance", MoneyCraftDesktopFrame.VIEW_TRIAL_BALANCE),
            MenuNode.leaf('D', "Day Book", MoneyCraftDesktopFrame.VIEW_DAY_BOOK),
            MenuNode.leaf('C', "Cash Flow", "REPORT:CASH_FLOW"),
            MenuNode.leaf('F', "Funds Flow / Schedule III", "REPORT:SCHEDULE_III"),
            MenuNode.branch('A', "Account Books", List.of(
                MenuNode.leaf('C', "Cash/Bank Book(s)", "BOOK:CASH_BANK_SUMMARY"),
                MenuNode.leaf('L', "Ledger Vouchers", "BOOK:LEDGER_VOUCHERS"),
                MenuNode.leaf('G', "Group Summary", "BOOK:GROUP_SUMMARY"),
                MenuNode.leaf('V', "Group Vouchers", "BOOK:GROUP_VOUCHERS"),
                MenuNode.leaf('O', "Contra Register", "BOOK:CONTRA_REGISTER"),
                MenuNode.leaf('P', "Payment Register", "BOOK:PAYMENT_REGISTER"),
                MenuNode.leaf('R', "Receipt Register", "BOOK:RECEIPT_REGISTER"),
                MenuNode.leaf('S', "Sales Register", "BOOK:SALES_REGISTER"),
                MenuNode.leaf('U', "Purchase Register", "BOOK:PURCHASE_REGISTER"),
                MenuNode.leaf('J', "Journal Register", "BOOK:JOURNAL_REGISTER"),
                MenuNode.leaf('B', "Debit Note Register", "BOOK:DEBIT_NOTE_REGISTER"),
                MenuNode.leaf('N', "Credit Note Register", "BOOK:CREDIT_NOTE_REGISTER")
            )),
            MenuNode.branch('S', "Statements of Accounts", List.of(
                MenuNode.leaf('R', "Bills Receivable", "STATEMENT:BILLS_RECEIVABLE"),
                MenuNode.leaf('P', "Bills Payable", "STATEMENT:BILLS_PAYABLE"),
                MenuNode.leaf('L', "Ledger Outstandings", "STATEMENT:LEDGER_OUTSTANDINGS"),
                MenuNode.leaf('G', "Group Outstandings", "STATEMENT:GROUP_OUTSTANDINGS"),
                MenuNode.leaf('A', "Ageing Analysis", "STATEMENT:AGEING"),
                MenuNode.leaf('C', "Cost Centre Summary", "STATEMENT:COST_CENTRES"),
                MenuNode.leaf('T', "Statistics", "STATEMENT:STATISTICS")
            )),
            MenuNode.branch('I', "Inventory Books", List.of(
                MenuNode.leaf('I', "Stock Item Monthly Summary", "INV_BOOK:ITEM_MONTHLY"),
                MenuNode.leaf('G', "Stock Group Summary", "INV_BOOK:GROUP_SUMMARY"),
                MenuNode.leaf('T', "Stock Transfers Register", "INV_BOOK:STOCK_TRANSFERS"),
                MenuNode.leaf('P', "Physical Stock Register", "INV_BOOK:PHYSICAL_STOCK"),
                MenuNode.leaf('M', "Movement Analysis", "INV_BOOK:MOVEMENT_ANALYSIS"),
                MenuNode.leaf('B', "Batch & Expiry Ageing", "INV_BOOK:BATCH_AGEING"),
                MenuNode.leaf('S', "Sales Order Outstandings", "INV_BOOK:SALES_ORDERS"),
                MenuNode.leaf('U', "Purchase Order Outstandings", "INV_BOOK:PURCHASE_ORDERS")
            )),
            MenuNode.branch('E', "Statements of Inventory", List.of(
                MenuNode.leaf('G', "Godown / Location Summary", "INV_STMT:GODOWN_SUMMARY"),
                MenuNode.leaf('C', "Stock Category Summary", "INV_STMT:CATEGORY_SUMMARY"),
                MenuNode.leaf('Q', "Stock Query", "INV_STMT:STOCK_QUERY"),
                MenuNode.leaf('B', "BOM Cost Estimation", "INV_STMT:BOM_ESTIMATION")
            )),
            MenuNode.branch('O', "Statutory Reports", List.of(
                MenuNode.leaf('1', "GSTR-1", "STAT_REPORT:GSTR_1"),
                MenuNode.leaf('2', "GSTR-2B Reconciliation", "STAT_REPORT:GSTR_2B"),
                MenuNode.leaf('I', "IMS Inward Workbench", "STAT_REPORT:IMS"),
                MenuNode.leaf('3', "GSTR-3B", "STAT_REPORT:GSTR_3B"),
                MenuNode.leaf('9', "One-Click GSTR-9 Annual", "STAT_REPORT:GSTR_9"),
                MenuNode.leaf('E', "e-Invoice Register", "STAT_REPORT:EINVOICE"),
                MenuNode.leaf('W', "e-Way Bill Register", "STAT_REPORT:EWAY_BILL"),
                MenuNode.leaf('Q', "Form 26Q Quarterly TDS", "STAT_REPORT:TDS_26Q"),
                MenuNode.leaf('C', "Challan ITNS 281 Register", "STAT_REPORT:TDS_CHALLAN")
            )),
            MenuNode.branch('X', "Exception Reports", List.of(
                MenuNode.leaf('S', "Negative Stock", "EXCEPTION:NEGATIVE_STOCK"),
                MenuNode.leaf('L', "Negative Ledgers", "EXCEPTION:NEGATIVE_LEDGERS"),
                MenuNode.leaf('R', "Overdue Receivables", "EXCEPTION:OVERDUE_RECEIVABLES"),
                MenuNode.leaf('P', "Overdue Payables", "EXCEPTION:OVERDUE_PAYABLES"),
                MenuNode.leaf('O', "Optional Vouchers", "EXCEPTION:OPTIONAL_VOUCHERS"),
                MenuNode.leaf('C', "Cancelled Vouchers", "EXCEPTION:CANCELLED_VOUCHERS"),
                MenuNode.leaf('M', "Memorandum Vouchers", "EXCEPTION:MEMORANDUM_VOUCHERS"),
                MenuNode.leaf('J', "Reversing Journals", "EXCEPTION:REVERSING_JOURNALS"),
                MenuNode.leaf('A', "Voucher Approval Queue", "EXCEPTION:APPROVAL_QUEUE")
            )),
            MenuNode.branch('V', "Analysis & Verification", List.of(
                MenuNode.leaf('E', "MCA Edit Log Explorer", "AUDIT:EDIT_LOG"),
                MenuNode.leaf('H', "MC-AUDIT-2 Hash-Chain Verification", "AUDIT:HASH_CHAIN"),
                MenuNode.leaf('O', "Trial Balance Opening Difference Check", "AUDIT:OPENING_DIFF"),
                MenuNode.leaf('G', "Government & Banking Interface Inventory", "AUDIT:GOV_INTEGRATIONS")
            ))
        );

        List<MenuNode> chartOfAccountsChildren = new ArrayList<>();
        chartOfAccountsChildren.add(MenuNode.leaf('T', "Chart of Accounts Tree Explorer", "MASTER:COA_TREE"));
        chartOfAccountsChildren.addAll(createAndAlterChildren);

        return List.of(
            MenuNode.branch('C', "Create", createAndAlterChildren),
            MenuNode.branch('A', "Alter", createAndAlterChildren),
            MenuNode.branch('H', "Chart of Accounts", List.copyOf(chartOfAccountsChildren)),
            MenuNode.leaf('V', "Vouchers", MoneyCraftDesktopFrame.VIEW_VOUCHER_ENTRY),
            MenuNode.leaf('K', "Day Book", MoneyCraftDesktopFrame.VIEW_DAY_BOOK),
            MenuNode.branch('N', "Banking", List.of(
                MenuNode.leaf('P', "Cheque Printing", "BANKING:CHEQUE_PRINT"),
                MenuNode.leaf('R', "Cheque Register", "BANKING:CHEQUE_REGISTER"),
                MenuNode.leaf('B', "Bank Reconciliation (Auto/Manual BRS)", "BANKING:BRS"),
                MenuNode.leaf('D', "Deposit Slip", "BANKING:DEPOSIT_SLIP"),
                MenuNode.leaf('A', "Payment Advice", "BANKING:PAYMENT_ADVICE"),
                MenuNode.leaf('S', "Post-Dated Summary", "BANKING:POST_DATED"),
                MenuNode.leaf('O', "Bank Statement / OCR Staging", "BANKING:OCR_STAGING")
            )),
            MenuNode.branch('M', "Import & Exchange", List.of(
                MenuNode.leaf('S', "Guided Spreadsheet Import (.xlsx/.ods/.csv)", "EXCHANGE:SPREADSHEET"),
                MenuNode.leaf('L', "Migrate Legacy .1800 Snapshot", "EXCHANGE:LEGACY_1800"),
                MenuNode.leaf('X', "XML / JSONEx Import & Export", "EXCHANGE:XML_JSONEX"),
                MenuNode.leaf('T', "Mapping Templates", "EXCHANGE:TEMPLATES")
            )),
            MenuNode.leaf('E', "Extension & TDL Workbench", "EXTENSION:WORKBENCH"),
            MenuNode.leaf('B', "Balance Sheet", "REPORT:BALANCE_SHEET"),
            MenuNode.leaf('P', "Profit & Loss A/c", "REPORT:PROFIT_AND_LOSS"),
            MenuNode.leaf('S', "Stock Summary", "REPORT:STOCK_SUMMARY"),
            MenuNode.leaf('R', "Ratio Analysis", "REPORT:RATIO_ANALYSIS"),
            MenuNode.branch('D', "Display More Reports", displayMoreChildren)
        );
    }

    public record MenuNode(
        char mnemonic,
        String label,
        String targetActionId,
        List<MenuNode> children
    ) {
        public MenuNode {
            Objects.requireNonNull(label, "label must not be null");
            children = children == null ? List.of() : List.copyOf(children);
        }

        public static MenuNode branch(char mnemonic, String label, List<MenuNode> children) {
            return new MenuNode(mnemonic, label, null, children);
        }

        public static MenuNode leaf(char mnemonic, String label, String targetActionId) {
            return new MenuNode(mnemonic, label, Objects.requireNonNull(targetActionId), List.of());
        }
    }

    public record DrillOutcome(
        boolean drilledIntoSubmenu,
        String leafTargetActionId,
        String breadcrumb
    ) {
    }

    private record MenuLevelFrame(
        String title,
        List<MenuNode> items,
        int parentSelectedIndex
    ) {
    }
}
