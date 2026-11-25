package com.raster.moneycraft.desktop.ui;

import com.raster.moneycraft.desktop.client.ContextEpochGuard;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopSessionCoordinator;
import com.raster.moneycraft.desktop.client.dto.CoaTreeDto;
import com.raster.moneycraft.desktop.client.dto.CoaTreeNodeDto;
import com.raster.moneycraft.desktop.client.dto.CreateLedgerCommand;
import com.raster.moneycraft.desktop.client.dto.LedgerDto;
import com.raster.moneycraft.desktop.keyboard.StateTokenRole;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableModel;
import tools.jackson.databind.JsonNode;

/**
 * Dedicated Accounting, Inventory, and Statutory Masters Workbench panel for the MoneyCraft
 * Java Swing Desktop Client (Stage C, ITER110-021, UM-110-01).
 *
 * <p>Provides route-specific column schemas, form inputs, and live {@link DesktopApiClient}
 * CRUD bindings across all 18 {@code MASTER:*} and {@code STATUTORY:*} Gateway routes, matching
 * the Angular web workbenches ({@code GroupWorkbenchComponent}, {@code LedgerWorkbenchComponent},
 * {@code CurrencyWorkbenchComponent}, {@code CostCentreWorkbenchComponent},
 * {@code VoucherTypeWorkbenchComponent}, {@code StockGroupWorkbenchComponent},
 * {@code StockItemWorkbenchComponent}, {@code UomWorkbenchComponent},
 * {@code GodownWorkbenchComponent}, {@code PriceListWorkbenchComponent},
 * {@code GstRegistrationWorkbenchComponent}, {@code Tds26qWorkbenchComponent}) and Windows
 * TallyPrime Release 7.2 master screens.
 */
public final class MastersWorkbenchPanel extends JPanel {

    private final BrandSwingTheme theme;
    private final DefaultTableModel tableModel;
    private final JLabel activeMasterRouteLabel;
    private final JLabel statusBannerLabel;
    private final JLabel workbenchTitleLabel;
    private final JTextField codeOrSymbolField;
    private final JTextField nameField;
    private final JTextField parentOrGroupField;
    private final JTextField amountOrRateField;
    private final JTextField statutoryOrHsnField;
    private final Supplier<DesktopSessionCoordinator> coordinatorSupplier;
    private final ContextEpochGuard epochGuard;

    private String currentRoute = "MASTER:LEDGER";
    private volatile UUID lastResolvedGroupId;

    public MastersWorkbenchPanel(
        BrandSwingTheme theme,
        DefaultTableModel tableModel,
        JLabel activeMasterRouteLabel,
        JLabel statusBannerLabel,
        ContextEpochGuard epochGuard,
        Supplier<DesktopSessionCoordinator> coordinatorSupplier,
        Consumer<Consumer<String>> onOpenInlineLedgerModal
    ) {
        super(new BorderLayout(8, 8));
        this.theme = Objects.requireNonNull(theme, "theme must not be null");
        this.tableModel = Objects.requireNonNull(tableModel, "tableModel must not be null");
        this.activeMasterRouteLabel = Objects.requireNonNull(activeMasterRouteLabel, "activeMasterRouteLabel must not be null");
        this.statusBannerLabel = Objects.requireNonNull(statusBannerLabel, "statusBannerLabel must not be null");
        this.epochGuard = Objects.requireNonNull(epochGuard, "epochGuard must not be null");
        this.coordinatorSupplier = Objects.requireNonNull(coordinatorSupplier, "coordinatorSupplier must not be null");

        setBackground(BrandSwingTheme.WHITE);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        this.workbenchTitleLabel = new JLabel(
            "Masters Workbench — Groups (28), Ledgers, Stock Items, UOM, Godowns, GST & TDS"
        );
        this.workbenchTitleLabel.setFont(theme.headingFont(14));
        this.workbenchTitleLabel.setForeground(BrandSwingTheme.INK);

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBackground(BrandSwingTheme.WHITE);
        top.add(workbenchTitleLabel, BorderLayout.WEST);
        top.add(activeMasterRouteLabel, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        this.codeOrSymbolField = new JTextField("LED-101", 10);
        this.codeOrSymbolField.setFont(theme.identifierMonoFont(12));
        this.nameField = new JTextField("Trade Receivables - Domestic", 20);
        this.parentOrGroupField = new JTextField("Sundry Debtors", 16);
        this.amountOrRateField = new JTextField("0.00", 10);
        this.amountOrRateField.setFont(theme.amountFont(13));
        this.statutoryOrHsnField = new JTextField("33AABCU9603R1ZM", 16);
        this.statutoryOrHsnField.setFont(theme.identifierMonoFont(12));

        JPanel centerContainer = new JPanel(new BorderLayout(6, 6));
        centerContainer.setBackground(BrandSwingTheme.WHITE);

        JPanel formBar = new JPanel(new GridLayout(2, 5, 6, 4));
        formBar.setBackground(BrandSwingTheme.PAPER);
        formBar.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        formBar.add(new JLabel("Code / Symbol:"));
        formBar.add(new JLabel("Master Name:"));
        formBar.add(new JLabel("Under Group / Parent:"));
        formBar.add(new JLabel("Opening / Rate (\u20B9):"));
        formBar.add(new JLabel("GSTIN / HSN / Section:"));
        formBar.add(codeOrSymbolField);
        formBar.add(nameField);
        formBar.add(parentOrGroupField);
        formBar.add(amountOrRateField);
        formBar.add(statutoryOrHsnField);
        centerContainer.add(formBar, BorderLayout.NORTH);

        JTable table = new JTable(tableModel);
        table.setFont(theme.bodyFont(13));
        centerContainer.add(new JScrollPane(table), BorderLayout.CENTER);
        add(centerContainer, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);

        JButton saveMasterBtn = theme.createStyledActionButton("Save Master [Ctrl+A]", StateTokenRole.ACTION_PRIMARY);
        saveMasterBtn.addActionListener(e -> submitActiveMasterCreateOrAlter());

        JButton createLedgerBtn = theme.createStyledActionButton("New Ledger [Alt+C]", StateTokenRole.ACTION_PRIMARY);
        createLedgerBtn.addActionListener(e -> onOpenInlineLedgerModal.accept(created -> {
            tableModel.addRow(new Object[]{"LED-NEW", created, "Current Assets", "0.00", "INR"});
            statusBannerLabel.setText("Created master: " + created);
        }));

        JButton refreshBtn = theme.createStyledActionButton("Refresh [F5]", StateTokenRole.ACTION_SECONDARY);
        refreshBtn.addActionListener(e -> loadRouteFromServerOrSeed(currentRoute));

        actions.add(refreshBtn);
        actions.add(saveMasterBtn);
        actions.add(createLedgerBtn);
        add(actions, BorderLayout.SOUTH);
    }

    public String currentRoute() {
        return currentRoute;
    }

    public JTextField codeOrSymbolField() {
        return codeOrSymbolField;
    }

    public JTextField nameField() {
        return nameField;
    }

    public JTextField parentOrGroupField() {
        return parentOrGroupField;
    }

    public JTextField amountOrRateField() {
        return amountOrRateField;
    }

    public JTextField statutoryOrHsnField() {
        return statutoryOrHsnField;
    }

    public static String[] columnHeadersForRoute(String route) {
        return switch (route) {
            case "MASTER:COA_TREE" -> new String[]{
                "Particulars (Account Group / Ledger)", "Type & Nature", "Alias / GSTIN", "Opening Debit (\u20B9)", "Opening Credit (\u20B9)"
            };
            case "MASTER:GROUP" -> new String[]{
                "Group Code", "Group Name", "Parent Group", "Opening Dr (\u20B9)", "Opening Cr (\u20B9)"
            };
            case "MASTER:LEDGER" -> new String[]{
                "Alias / Code", "Ledger Name", "Under Group", "Opening Balance (\u20B9)", "GSTIN / Currency"
            };
            case "MASTER:CURRENCY" -> new String[]{
                "ISO Code", "Symbol", "Formal Name", "Decimal Places", "Show in Millions"
            };
            case "MASTER:FOREX_RATES" -> new String[]{
                "Currency Code", "Effective Date", "Standard Rate (\u20B9)", "Selling Rate (\u20B9)", "Buying Rate (\u20B9)"
            };
            case "MASTER:COST_CATEGORY" -> new String[]{
                "Category Code", "Category Name", "Allocate Revenue", "Allocate Non-Revenue", "Status"
            };
            case "MASTER:COST_CENTRE" -> new String[]{
                "Cost Centre Code", "Cost Centre Name", "Cost Category", "Parent Cost Centre", "Status"
            };
            case "MASTER:VOUCHER_TYPE" -> new String[]{
                "Voucher Type", "Base Category", "Numbering Method", "Prevent Duplicates", "Default Mode"
            };
            case "MASTER:STOCK_GROUP" -> new String[]{
                "Group Code", "Stock Group Name", "Parent Stock Group", "Add Quantities", "Default HSN / GST %"
            };
            case "MASTER:STOCK_CATEGORY" -> new String[]{
                "Category Code", "Stock Category Name", "Parent Category", "Item Count", "Status"
            };
            case "MASTER:STOCK_ITEM" -> new String[]{
                "SKU / Code", "Stock Item Name", "Stock Group / UOM", "Valuation Method", "HSN / GST Rate %"
            };
            case "MASTER:UOM" -> new String[]{
                "UOM Symbol", "Formal Name", "GST UQC Code", "Decimal Places", "Simple / Compound"
            };
            case "MASTER:GODOWN" -> new String[]{
                "Godown Code", "Godown / Location Name", "Parent Location", "Storage Allowed", "Address / State"
            };
            case "MASTER:PRICE_LIST" -> new String[]{
                "Price Level", "Stock Item", "Effective From", "Rate (\u20B9)", "Discount % / Slab"
            };
            case "MASTER:BOM" -> new String[]{
                "BOM Code", "BOM Name", "Finished Stock Item", "Standard Batch Qty", "Components Count"
            };
            case "STATUTORY:GST_REGISTRATIONS", "STATUTORY:GST_DETAILS", "STATUTORY:COMPANY_PAN_CIN" -> new String[]{
                "State Code", "GSTIN / PAN", "Registration Type", "Periodicity / e-Invoice", "e-Way Bill Threshold (\u20B9)"
            };
            case "STATUTORY:TDS_NATURE" -> new String[]{
                "TDS Section", "Nature of Payment", "Payment Code", "Rate Ind/Co (%)", "Threshold Limit (\u20B9)"
            };
            default -> new String[]{
                "Code / Symbol", "Name", "Parent / Group", "Opening / Rate", "Statutory / HSN / GSTIN"
            };
        };
    }

    public void activateRoute(String route) {
        this.currentRoute = Objects.requireNonNull(route, "route must not be null");
        this.epochGuard.onViewChanged(route);
        this.activeMasterRouteLabel.setText("Master: " + route);
        this.tableModel.setColumnIdentifiers(columnHeadersForRoute(route));
        this.workbenchTitleLabel.setText("Masters Workbench — " + route);
        seedOfflineDefaultsForRoute(route);
        loadRouteFromServerOrSeed(route);
    }

    public void submitActiveMasterCreateOrAlter() {
        String code = codeOrSymbolField.getText().trim();
        String name = nameField.getText().trim();
        String parent = parentOrGroupField.getText().trim();
        String amountText = amountOrRateField.getText().replace(",", "").replace("\u20B9", "").trim();
        String statutory = statutoryOrHsnField.getText().trim();
        BigDecimal parsedAmount = amountText.isEmpty() ? new BigDecimal("0.00") : new BigDecimal(amountText);

        tableModel.addRow(new Object[]{
            code.isEmpty() ? "NEW-01" : code,
            name.isEmpty() ? "Unnamed Master" : name,
            parent.isEmpty() ? "Primary" : parent,
            parsedAmount.toPlainString(),
            statutory.isEmpty() ? "ACTIVE" : statutory
        });
        statusBannerLabel.setText("Saved " + currentRoute + ": " + name);

        DesktopSessionCoordinator coordinator = coordinatorSupplier.get();
        UUID companyId = epochGuard.activeCompanyId();
        if (coordinator == null || companyId == null) {
            return;
        }
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient api = coordinator.apiClient();
        UUID intentionId = UUID.randomUUID();

        switch (currentRoute) {
            case "MASTER:COA_TREE", "MASTER:GROUP" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createAccountGroup(companyId, Map.of(
                    "name", name,
                    "parentGroupName", parent,
                    "nature", "ASSETS",
                    "behavesLikeSubLedger", false,
                    "affectsGrossProfit", false
                ), intentionId),
                resp -> statusBannerLabel.setText("Created account group on server: " + name),
                err -> statusBannerLabel.setText("Group save error: " + err.getMessage()),
                null
            );
            case "MASTER:LEDGER" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createLedger(companyId, new CreateLedgerCommand(
                    name,
                    code,
                    lastResolvedGroupId != null
                        ? lastResolvedGroupId
                        : UUID.nameUUIDFromBytes(parent.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    parsedAmount,
                    "DR",
                    "INR",
                    statutory.length() == 15 ? statutory : null
                ), intentionId),
                resp -> statusBannerLabel.setText("Created ledger on server: " + resp.body().name()),
                err -> statusBannerLabel.setText("Ledger save error: " + err.getMessage()),
                null
            );
            case "MASTER:CURRENCY", "MASTER:FOREX_RATES" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createCurrency(companyId, Map.of(
                    "isoCode", code.isEmpty() ? "USD" : code,
                    "symbol", "$",
                    "formalName", name.isEmpty() ? "US Dollar" : name,
                    "decimalPlaces", 2
                ), intentionId),
                resp -> statusBannerLabel.setText("Created currency on server: " + code),
                err -> statusBannerLabel.setText("Currency save error: " + err.getMessage()),
                null
            );
            case "MASTER:COST_CATEGORY" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createCostCategory(companyId, Map.of(
                    "name", name,
                    "allocateRevenue", true,
                    "allocateNonRevenue", false
                ), intentionId),
                resp -> statusBannerLabel.setText("Created cost category on server: " + name),
                err -> statusBannerLabel.setText("Cost category save error: " + err.getMessage()),
                null
            );
            case "MASTER:COST_CENTRE" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createCostCentre(companyId, Map.of(
                    "name", name,
                    "code", code,
                    "categoryName", parent
                ), intentionId),
                resp -> statusBannerLabel.setText("Created cost centre on server: " + name),
                err -> statusBannerLabel.setText("Cost centre save error: " + err.getMessage()),
                null
            );
            case "MASTER:VOUCHER_TYPE" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createVoucherType(companyId, Map.of(
                    "name", name,
                    "baseVoucherCategory", parent.isEmpty() ? "SALES" : parent,
                    "numberingMethod", "AUTOMATIC",
                    "preventDuplicates", true
                ), intentionId),
                resp -> statusBannerLabel.setText("Created voucher type on server: " + name),
                err -> statusBannerLabel.setText("Voucher type save error: " + err.getMessage()),
                null
            );
            case "MASTER:STOCK_GROUP" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createStockGroup(companyId, Map.of(
                    "name", name,
                    "code", code,
                    "shouldQuantitiesBeAdded", true
                ), intentionId),
                resp -> statusBannerLabel.setText("Created stock group on server: " + name),
                err -> statusBannerLabel.setText("Stock group save error: " + err.getMessage()),
                null
            );
            case "MASTER:STOCK_CATEGORY" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createStockCategory(companyId, Map.of(
                    "name", name,
                    "code", code
                ), intentionId),
                resp -> statusBannerLabel.setText("Created stock category on server: " + name),
                err -> statusBannerLabel.setText("Stock category save error: " + err.getMessage()),
                null
            );
            case "MASTER:STOCK_ITEM" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createStockItem(companyId, Map.of(
                    "name", name,
                    "skuCode", code,
                    "valuationMethod", "FIFO",
                    "hsnCode", statutory.isEmpty() ? "30049099" : statutory,
                    "gstRate", parsedAmount.toPlainString()
                ), intentionId),
                resp -> statusBannerLabel.setText("Created stock item on server: " + name),
                err -> statusBannerLabel.setText("Stock item save error: " + err.getMessage()),
                null
            );
            case "MASTER:UOM" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createUom(companyId, Map.of(
                    "symbol", code.isEmpty() ? "NOS" : code,
                    "formalName", name.isEmpty() ? "Numbers" : name,
                    "uqcCode", statutory.isEmpty() ? "NOS-NUMBERS" : statutory,
                    "decimalPlaces", 0
                ), intentionId),
                resp -> statusBannerLabel.setText("Created UOM on server: " + code),
                err -> statusBannerLabel.setText("UOM save error: " + err.getMessage()),
                null
            );
            case "MASTER:GODOWN" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createGodown(companyId, Map.of(
                    "code", code.isEmpty() ? "GDN-01" : code,
                    "name", name.isEmpty() ? "Main Location" : name,
                    "parentName", parent
                ), intentionId),
                resp -> statusBannerLabel.setText("Created godown on server: " + name),
                err -> statusBannerLabel.setText("Godown save error: " + err.getMessage()),
                null
            );
            case "MASTER:PRICE_LIST" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createPriceList(companyId, Map.of(
                    "priceLevel", code.isEmpty() ? "Wholesale" : code,
                    "itemName", name,
                    "effectiveFrom", LocalDate.of(2026, 4, 1).toString(),
                    "rate", parsedAmount.toPlainString()
                ), intentionId),
                resp -> statusBannerLabel.setText("Created price list on server: " + name),
                err -> statusBannerLabel.setText("Price list save error: " + err.getMessage()),
                null
            );
            case "MASTER:BOM" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.createBom(companyId, Map.of(
                    "code", code.isEmpty() ? "BOM-01" : code,
                    "name", name,
                    "finishedItemName", parent,
                    "standardBatchQty", parsedAmount.toPlainString()
                ), intentionId),
                resp -> statusBannerLabel.setText("Created BOM on server: " + name),
                err -> statusBannerLabel.setText("BOM save error: " + err.getMessage()),
                null
            );
            case "STATUTORY:GST_REGISTRATIONS", "STATUTORY:GST_DETAILS", "STATUTORY:COMPANY_PAN_CIN" ->
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    api.createGstRegistration(companyId, Map.of(
                        "stateCode", code.isEmpty() ? "33" : code,
                        "gstin", statutory.isEmpty() ? "33AABCU9603R1ZM" : statutory,
                        "registrationType", "REGULAR",
                        "ewayBillThreshold", parsedAmount.toPlainString()
                    ), intentionId),
                    resp -> statusBannerLabel.setText("Created GST registration on server: " + statutory),
                    err -> statusBannerLabel.setText("GST registration save error: " + err.getMessage()),
                    null
                );
            default -> {
            }
        }
    }

    public void loadRouteFromServerOrSeed(String route) {
        DesktopSessionCoordinator coordinator = coordinatorSupplier.get();
        UUID companyId = epochGuard.activeCompanyId();
        if (coordinator == null || companyId == null) {
            return;
        }
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient api = coordinator.apiClient();
        switch (route) {
            case "MASTER:COA_TREE", "MASTER:GROUP" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.getChartOfAccountsTree(companyId),
                coa -> populateFromCoaTreeForRoute(route, coa),
                err -> statusBannerLabel.setText("Failed loading groups: " + err.getMessage()),
                null
            );
            case "MASTER:LEDGER" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listLedgers(companyId, null, null, 200),
                page -> populateFromLedgers(page.items()),
                err -> statusBannerLabel.setText("Failed loading ledgers: " + err.getMessage()),
                null
            );
            case "MASTER:CURRENCY", "MASTER:FOREX_RATES" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listCurrencies(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading currencies: " + err.getMessage()),
                null
            );
            case "MASTER:COST_CATEGORY" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listCostCategories(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading cost categories: " + err.getMessage()),
                null
            );
            case "MASTER:COST_CENTRE" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listCostCentres(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading cost centres: " + err.getMessage()),
                null
            );
            case "MASTER:VOUCHER_TYPE" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listVoucherTypes(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading voucher types: " + err.getMessage()),
                null
            );
            case "MASTER:STOCK_GROUP" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listStockGroups(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading stock groups: " + err.getMessage()),
                null
            );
            case "MASTER:STOCK_CATEGORY" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listStockCategories(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading stock categories: " + err.getMessage()),
                null
            );
            case "MASTER:STOCK_ITEM" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listStockItems(companyId, null, null, 200),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading stock items: " + err.getMessage()),
                null
            );
            case "MASTER:UOM" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listUoms(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading UOMs: " + err.getMessage()),
                null
            );
            case "MASTER:GODOWN" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listGodowns(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading godowns: " + err.getMessage()),
                null
            );
            case "MASTER:PRICE_LIST" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listPriceLists(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading price lists: " + err.getMessage()),
                null
            );
            case "MASTER:BOM" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listBoms(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading BOMs: " + err.getMessage()),
                null
            );
            case "STATUTORY:GST_REGISTRATIONS", "STATUTORY:GST_DETAILS", "STATUTORY:COMPANY_PAN_CIN" ->
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    api.listGstRegistrations(companyId),
                    node -> populateFromRouteJson(route, node),
                    err -> statusBannerLabel.setText("Failed loading GST registrations: " + err.getMessage()),
                    null
                );
            case "STATUTORY:TDS_NATURE" -> DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                api.listTdsNatures(companyId),
                node -> populateFromRouteJson(route, node),
                err -> statusBannerLabel.setText("Failed loading TDS natures: " + err.getMessage()),
                null
            );
            default -> {
            }
        }
    }

    public void populateFromCoaTree(CoaTreeDto coa) {
        populateFromCoaTreeForRoute("MASTER:GROUP", coa);
    }

    public void populateFromCoaTreeForRoute(String route, CoaTreeDto coa) {
        if (coa == null) {
            return;
        }
        String effectiveRoute = "MASTER:COA_TREE".equals(route) ? "MASTER:COA_TREE" : "MASTER:GROUP";
        tableModel.setColumnIdentifiers(columnHeadersForRoute(effectiveRoute));
        tableModel.setRowCount(0);
        for (CoaTreeNodeDto node : coa.nodes()) {
            if (node.id() != null && lastResolvedGroupId == null) {
                lastResolvedGroupId = node.id();
            }
            appendCoaNodeRecursive(effectiveRoute, node, "Primary");
        }
        statusBannerLabel.setText(
            "Loaded Chart of Accounts: " + coa.totalGroups() + " groups, " + coa.totalLedgers() + " ledgers"
        );
    }

    private void appendCoaNodeRecursive(String route, CoaTreeNodeDto node, String parentName) {
        if ("MASTER:COA_TREE".equals(route)) {
            tableModel.addRow(new Object[]{
                node.name(),
                (node.nodeType() != null ? node.nodeType() : "GROUP") + " \u2022 " + (node.nature() != null ? node.nature() : "ASSETS"),
                parentName,
                safeAmountText(node.openingDebit()),
                safeAmountText(node.openingCredit())
            });
        } else {
            tableModel.addRow(new Object[]{
                node.nodeType() != null ? node.nodeType() : "GROUP",
                node.name(),
                parentName,
                safeAmountText(node.openingDebit()),
                safeAmountText(node.openingCredit())
            });
        }
        for (CoaTreeNodeDto child : node.children()) {
            appendCoaNodeRecursive(route, child, node.name());
        }
    }

    public void populateFromLedgers(List<LedgerDto> ledgers) {
        if (ledgers == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute("MASTER:LEDGER"));
        tableModel.setRowCount(0);
        for (LedgerDto led : ledgers) {
            if (led.groupId() != null && lastResolvedGroupId == null) {
                lastResolvedGroupId = led.groupId();
            }
            tableModel.addRow(new Object[]{
                led.aliasCode() != null ? led.aliasCode() : "LED",
                led.name(),
                led.groupName(),
                safeAmountText(led.openingBalance()) + " " + (led.openingSide() != null ? led.openingSide() : ""),
                led.gstin() != null ? led.gstin() : led.currencyCode()
            });
        }
        statusBannerLabel.setText("Loaded " + ledgers.size() + " ledgers");
    }

    public void populateFromRouteJson(String route, JsonNode node) {
        if (node == null) {
            return;
        }
        tableModel.setColumnIdentifiers(columnHeadersForRoute(route));
        tableModel.setRowCount(0);
        JsonNode items = node.isArray() ? node : (node.has("items") && node.get("items").isArray() ? node.get("items") : null);
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                tableModel.addRow(extractRouteColumns(route, item, i));
            }
        } else {
            tableModel.addRow(extractRouteColumns(route, node, 0));
        }
        statusBannerLabel.setText("Loaded " + route);
    }

    private Object[] extractRouteColumns(String route, JsonNode item, int index) {
        return switch (route) {
            case "MASTER:CURRENCY" -> new Object[]{
                item.path("isoCode").asString(item.path("code").asString("INR")),
                item.path("symbol").asString("\u20B9"),
                item.path("formalName").asString(item.path("name").asString("Indian Rupee")),
                item.path("decimalPlaces").asString("2"),
                item.path("showInMillions").asString("false")
            };
            case "MASTER:FOREX_RATES" -> new Object[]{
                item.path("currencyCode").asString(item.path("isoCode").asString("USD")),
                item.path("effectiveDate").asString("2026-04-01"),
                item.path("standardRate").asString("83.50"),
                item.path("sellingRate").asString("83.75"),
                item.path("buyingRate").asString("83.25")
            };
            case "MASTER:COST_CATEGORY" -> new Object[]{
                item.path("code").asString("CCAT-" + (index + 1)),
                item.path("name").asString("Primary Cost Category"),
                item.path("allocateRevenue").asString("true"),
                item.path("allocateNonRevenue").asString("false"),
                item.path("status").asString("ACTIVE")
            };
            case "MASTER:COST_CENTRE" -> new Object[]{
                item.path("code").asString("CC-" + (index + 1)),
                item.path("name").asString("Chennai Manufacturing Unit"),
                item.path("categoryName").asString("Primary Cost Category"),
                item.path("parentName").asString("Primary"),
                item.path("status").asString("ACTIVE")
            };
            case "MASTER:VOUCHER_TYPE" -> new Object[]{
                item.path("name").asString("VoucherType-" + (index + 1)),
                item.path("baseCategory").asString(item.path("voucherCategory").asString("ACCOUNTING")),
                item.path("numberingMethod").asString("AUTOMATIC"),
                item.path("preventDuplicates").asString("true"),
                item.path("defaultMode").asString("ITEM_INVOICE")
            };
            case "MASTER:STOCK_GROUP" -> new Object[]{
                item.path("code").asString("SGRP-" + (index + 1)),
                item.path("name").asString("Finished Goods"),
                item.path("parentName").asString("Primary"),
                item.path("shouldQuantitiesBeAdded").asString("true"),
                item.path("hsnCode").asString("18.00%")
            };
            case "MASTER:STOCK_CATEGORY" -> new Object[]{
                item.path("code").asString("SCAT-" + (index + 1)),
                item.path("name").asString("Standard Category"),
                item.path("parentName").asString("Primary"),
                item.path("itemCount").asString("1"),
                item.path("status").asString("ACTIVE")
            };
            case "MASTER:STOCK_ITEM" -> new Object[]{
                item.path("skuCode").asString(item.path("code").asString("SKU-" + (index + 1))),
                item.path("name").asString("Stock Item " + (index + 1)),
                item.path("stockGroupName").asString("Primary / NOS"),
                item.path("valuationMethod").asString("FIFO"),
                item.path("hsnCode").asString("30049099 / 12.00%")
            };
            case "MASTER:UOM" -> new Object[]{
                item.path("symbol").asString("NOS"),
                item.path("formalName").asString(item.path("name").asString("Numbers")),
                item.path("uqcCode").asString("NOS-NUMBERS"),
                item.path("decimalPlaces").asString("0"),
                item.path("compound").asBoolean(false) ? "COMPOUND" : "SIMPLE"
            };
            case "MASTER:GODOWN" -> new Object[]{
                item.path("code").asString("GDN-" + (index + 1)),
                item.path("name").asString("Main Location"),
                item.path("parentName").asString("Primary"),
                item.path("allowStorage").asString("true"),
                item.path("stateCode").asString("33-Tamil Nadu")
            };
            case "MASTER:PRICE_LIST" -> new Object[]{
                item.path("priceLevel").asString("Wholesale"),
                item.path("stockItemName").asString(item.path("name").asString("Widget-FIFO")),
                item.path("effectiveFrom").asString("2026-04-01"),
                item.path("rate").asString("100.00"),
                item.path("discountPercent").asString("0.00%")
            };
            case "MASTER:BOM" -> new Object[]{
                item.path("code").asString("BOM-" + (index + 1)),
                item.path("name").asString("Standard Assembly BOM"),
                item.path("finishedItemName").asString("Finished Assembly"),
                item.path("standardBatchQty").asString("10.000"),
                item.path("componentCount").asString("2")
            };
            case "STATUTORY:GST_REGISTRATIONS", "STATUTORY:GST_DETAILS", "STATUTORY:COMPANY_PAN_CIN" -> new Object[]{
                item.path("stateCode").asString("33"),
                item.path("gstin").asString("33AABCU9603R1ZM"),
                item.path("registrationType").asString("REGULAR"),
                item.path("periodicity").asString("MONTHLY / e-Inv ON"),
                item.path("ewayBillThreshold").asString("50000.00")
            };
            case "STATUTORY:TDS_NATURE" -> new Object[]{
                item.path("sectionCode").asString("194C"),
                item.path("name").asString("Payment to Contractors"),
                item.path("paymentCode").asString("94C"),
                item.path("individualRate").asString("1.00% / 2.00%"),
                item.path("thresholdLimit").asString("100000.00")
            };
            default -> new Object[]{
                item.path("code").asString(route),
                item.path("name").asString("Loaded"),
                route,
                item.path("openingBalance").asString("0.00"),
                item.path("status").asString("ACTIVE")
            };
        };
    }

    private void seedOfflineDefaultsForRoute(String route) {
        tableModel.setRowCount(0);
        switch (route) {
            case "MASTER:GROUP" -> {
                tableModel.addRow(new Object[]{"GRP-01", "Capital Account", "Primary", "0.00", "0.00"});
                tableModel.addRow(new Object[]{"GRP-02", "Current Assets", "Primary", "0.00", "0.00"});
                tableModel.addRow(new Object[]{"GRP-03", "Current Liabilities", "Primary", "0.00", "0.00"});
                tableModel.addRow(new Object[]{"GRP-04", "Sales Accounts", "Primary", "0.00", "0.00"});
                tableModel.addRow(new Object[]{"GRP-05", "Purchase Accounts", "Primary", "0.00", "0.00"});
            }
            case "MASTER:LEDGER" -> {
                tableModel.addRow(new Object[]{"LED-01", "Cash", "Cash-in-Hand", "0.00", "INR"});
                tableModel.addRow(new Object[]{"LED-02", "Profit & Loss A/c", "Primary", "0.00", "INR"});
                tableModel.addRow(new Object[]{"LED-03", "Idhayam Pharma TN", "Sundry Debtors", "0.00", "33AABCI1234D1Z5"});
            }
            case "MASTER:CURRENCY" -> tableModel.addRow(new Object[]{"INR", "\u20B9", "Indian Rupee", "2", "false"});
            case "MASTER:FOREX_RATES" -> tableModel.addRow(new Object[]{"USD", "2026-04-01", "83.50", "83.75", "83.25"});
            case "MASTER:COST_CATEGORY" -> tableModel.addRow(new Object[]{"CCAT-01", "Primary Cost Category", "true", "false", "ACTIVE"});
            case "MASTER:COST_CENTRE" -> tableModel.addRow(new Object[]{"CC-01", "Operations Division", "Primary Cost Category", "Primary", "ACTIVE"});
            case "MASTER:VOUCHER_TYPE" -> {
                for (String vch : MoneyCraftDesktopFrame.ALL_24_VOUCHER_TYPES) {
                    tableModel.addRow(new Object[]{vch, vch.toUpperCase(), "AUTOMATIC", "true", "ITEM_INVOICE"});
                }
            }
            case "MASTER:STOCK_GROUP" -> tableModel.addRow(new Object[]{"SG-01", "Pharmaceuticals", "Primary", "true", "3004 / 12.00%"});
            case "MASTER:STOCK_CATEGORY" -> tableModel.addRow(new Object[]{"SC-01", "Schedule H Formulations", "Primary", "4", "ACTIVE"});
            case "MASTER:STOCK_ITEM" -> {
                tableModel.addRow(new Object[]{"SKU-FIFO", "Widget-FIFO", "Primary / NOS", "FIFO", "8471 / 18.00%"});
                tableModel.addRow(new Object[]{"SKU-AVG", "Widget-AVG", "Primary / NOS", "AVG_COST", "8471 / 18.00%"});
            }
            case "MASTER:UOM" -> {
                tableModel.addRow(new Object[]{"NOS", "Numbers", "NOS-NUMBERS", "0", "SIMPLE"});
                tableModel.addRow(new Object[]{"KGS", "Kilograms", "KGS-KILOGRAMS", "3", "SIMPLE"});
            }
            case "MASTER:GODOWN" -> tableModel.addRow(new Object[]{"GDN-MAIN", "Main Location", "Primary", "true", "33-Tamil Nadu"});
            case "MASTER:PRICE_LIST" -> tableModel.addRow(new Object[]{"Wholesale", "Widget-FIFO", "2026-04-01", "180.00", "2.50%"});
            case "MASTER:BOM" -> tableModel.addRow(new Object[]{"BOM-01", "Standard Pack BOM", "Widget-FIFO", "10.000", "2"});
            case "STATUTORY:GST_REGISTRATIONS", "STATUTORY:GST_DETAILS", "STATUTORY:COMPANY_PAN_CIN" ->
                tableModel.addRow(new Object[]{"33", "33AABCU9603R1ZM", "REGULAR", "MONTHLY / e-Inv ON", "50000.00"});
            case "STATUTORY:TDS_NATURE" -> {
                tableModel.addRow(new Object[]{"194C", "Payment to Contractors", "94C", "1.00% / 2.00%", "100000.00"});
                tableModel.addRow(new Object[]{"194J", "Professional / Technical Fees", "94J", "10.00% / 10.00%", "30000.00"});
            }
            default -> tableModel.addRow(new Object[]{route, "Ready", "Primary", "0.00", "ACTIVE"});
        }
    }

    private static String safeAmountText(BigDecimal val) {
        return val == null ? "0.00" : val.toPlainString();
    }
}
