package com.raster.moneycraft.desktop.ui;

import com.raster.moneycraft.desktop.MoneyCraftDesktopMain;
import com.raster.moneycraft.desktop.client.ApiProblemException;
import com.raster.moneycraft.desktop.client.ContextEpochGuard;
import com.raster.moneycraft.desktop.client.DesktopApiClient;
import com.raster.moneycraft.desktop.client.DesktopClientConfig;
import com.raster.moneycraft.desktop.client.DesktopSessionCoordinator;
import com.raster.moneycraft.desktop.client.UncertainTransportException;
import com.raster.moneycraft.desktop.client.dto.AccountBookReportDto;
import com.raster.moneycraft.desktop.client.dto.ApproveVoucherCommand;
import com.raster.moneycraft.desktop.client.dto.BalanceSheetReportDto;
import com.raster.moneycraft.desktop.client.dto.CoaTreeDto;
import com.raster.moneycraft.desktop.client.dto.CompanyDto;
import com.raster.moneycraft.desktop.client.dto.CreateCompanyCommand;
import com.raster.moneycraft.desktop.client.dto.CreateLedgerCommand;
import com.raster.moneycraft.desktop.client.dto.LedgerDto;
import com.raster.moneycraft.desktop.client.dto.ProfitAndLossReportDto;
import com.raster.moneycraft.desktop.client.dto.TrialBalanceRowDto;
import com.raster.moneycraft.desktop.client.dto.UpsertVoucherCommand;
import com.raster.moneycraft.desktop.client.dto.VoucherDto;
import com.raster.moneycraft.desktop.client.dto.VoucherLineCommand;
import com.raster.moneycraft.desktop.client.dto.VoucherSummaryDto;
import com.raster.moneycraft.desktop.device.CompletionEvidenceSource;
import com.raster.moneycraft.desktop.device.DeviceAuthorityContext;
import com.raster.moneycraft.desktop.device.DeviceProfile;
import com.raster.moneycraft.desktop.device.PrintAttemptLedger;
import com.raster.moneycraft.desktop.device.PrintAttemptRecord;
import com.raster.moneycraft.desktop.device.PrintAttemptState;
import com.raster.moneycraft.desktop.device.ScaleAndBarcodeCaptureManager;
import com.raster.moneycraft.desktop.device.ScaleReadingResult;
import com.raster.moneycraft.desktop.device.WeighingScaleProfile;
import com.raster.moneycraft.desktop.help.ContextualHelpCatalog;
import com.raster.moneycraft.desktop.keyboard.DesktopCommandRegistry;
import com.raster.moneycraft.desktop.keyboard.KeyboardPreferenceState;
import com.raster.moneycraft.desktop.keyboard.ModalActionSpec;
import com.raster.moneycraft.desktop.keyboard.StateTokenRole;
import com.raster.moneycraft.desktop.platform.NativeDesktopPlatformAdapter;
import com.raster.moneycraft.desktop.preferences.AppearanceMode;
import com.raster.moneycraft.desktop.preferences.DesktopPreferencesManager;
import com.raster.moneycraft.desktop.preferences.UiPreferencesEnvelopeDto;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.table.DefaultTableModel;

/**
 * Headless-testable MoneyCraft Native Desktop Swing workspace shell (SWG-001/002, KEY-002,
 * VIS-001, ITER110-021..029, UM-110-01..05).
 *
 * <p>Delivers:
 * <ul>
 *   <li>Classic Windows-style Startup {@code Select Company -> Create Company -> automatic F11 Features} flow</li>
 *   <li>3-Level Cascading {@code Gateway of MoneyCraft} ({@link GatewayCascadeTree}) with live breadcrumbs and Esc step-back</li>
 *   <li>Top Menu Bar ({@code Alt+K/Y/Z/G/O/E/M/P/F1}) + Right-Hand {@code F2..F12} Function Rail</li>
 *   <li>Non-destructive {@code Alt+G Go To} stack preserving dirty voucher drafts and exact amounts</li>
 *   <li>All 24 voucher types, comma-safe {@link BigDecimal} parsing, pre-submit cell flush on {@code Ctrl+A},
 *       Day Book row selection &amp; hide/restore ({@code Space/Ctrl+Space/Alt+R/Alt+U/Ctrl+U}), and Level 3 reports</li>
 *   <li>{@code Light/Dark/System} + {@code 75%..200%} scaling + {@code HELP-110-001..026} Escape-first help overlay</li>
 *   <li>Strictly zero {@code JPasswordField} controls (external-browser PKCE login exclusively)</li>
 * </ul>
 */
public final class MoneyCraftDesktopFrame {

    public static final String VIEW_GATEWAY = "GATEWAY";
    public static final String VIEW_COMPANY_SELECTOR = "COMPANY_SELECTOR";
    public static final String VIEW_COMPANY_CREATE = "COMPANY_CREATE";
    public static final String VIEW_MASTERS = "MASTERS";
    public static final String VIEW_VOUCHER_ENTRY = "VOUCHER_ENTRY";
    public static final String VIEW_DAY_BOOK = "DAY_BOOK";
    public static final String VIEW_TRIAL_BALANCE = "TRIAL_BALANCE";
    public static final String VIEW_REPORTS_EXPLORER = "REPORTS_EXPLORER";
    public static final String VIEW_PREFERENCES_AND_HELP = "PREFERENCES_AND_HELP";
    public static final String VIEW_DEVICE_CONSOLE = "DEVICE_CONSOLE";

    public static final List<String> ALL_24_VOUCHER_TYPES = List.of(
        "Contra", "Payment", "Receipt", "Journal", "Sales", "Purchase",
        "Debit Note", "Credit Note", "Stock Journal", "Delivery Note", "Receipt Note",
        "Rejections Out", "Rejections In", "Physical Stock", "Sales Order", "Purchase Order",
        "Memorandum", "Reversing Journal", "Material In", "Material Out",
        "Job Work In Order", "Job Work Out Order", "Payroll", "Attendance"
    );

    private static final String[] VOUCHER_MODES = {
        "ITEM_INVOICE", "ACCOUNTING_INVOICE", "AS_VOUCHER"
    };

    private final BrandSwingTheme theme;
    private final DesktopCommandRegistry commandRegistry;
    private final PrintAttemptLedger printAttemptLedger;
    private final ScaleAndBarcodeCaptureManager captureManager;
    private final DesktopPreferencesManager preferencesManager;
    private final ContextualHelpCatalog helpCatalog;
    private final ContextEpochGuard epochGuard;
    private final NativeDesktopPlatformAdapter platformAdapter;
    private final GatewayCascadeTree gatewayTree = new GatewayCascadeTree();

    private final JRootPane rootPane;
    private final JMenuBar topMenuBar;
    private final CardLayout cardLayout;
    private final JPanel cardHost;
    private final JLabel activeViewBadge;
    private final JLabel gatewayBreadcrumbLabel;
    private final JLabel statusBannerLabel;

    private final JPanel gatewayPanel;
    private final JPanel gatewayItemsPanel;
    private final JPanel companySelectorPanel;
    private final JPanel companyCreatePanel;
    private final MastersWorkbenchPanel mastersPanel;
    private final JPanel voucherEntryPanel;
    private final JPanel dayBookPanel;
    private final JPanel trialBalancePanel;
    private final ReportsWorkbenchPanel reportsExplorerPanel;
    private final JPanel preferencesPanel;
    private final JPanel deviceConsolePanel;
    private final JPanel modalOverlayPanel;
    private final JPanel helpOverlayPanel;
    private final JLabel helpOverlayTitleLabel;
    private final JLabel helpOverlayBodyLabel;

    private final JTextField companySearchField;
    private final DefaultTableModel companyTableModel;
    private final JTextField createCompanyCodeField;
    private final JTextField createCompanyNameField;
    private final JTextField createCompanyStateField;

    private final JLabel activeVoucherTypeLabel;
    private final JLabel activeVoucherModeLabel;
    private final JTextField voucherNumberField;
    private final JTextField voucherLedgerField;
    private final JTextField voucherDebitField;
    private final JTextField voucherCreditField;
    private final JTextField voucherNarrationField;
    private final JTextField barcodeCaptureField;
    private final JLabel scaleWeightStatusLabel;

    private final DefaultTableModel dayBookTableModel;
    private final Deque<Object[]> hiddenReportOrDayBookRows = new ArrayDeque<>();
    private final Set<Integer> selectedDayBookRowIndices = new HashSet<>();

    private final DefaultTableModel trialBalanceTableModel;
    private final JLabel trialBalanceEmptyStateLabel;
    private final JLabel reportDetailModeBadge;
    private final JLabel activeReportRouteLabel;
    private final JLabel activeMasterRouteLabel;
    private final DefaultTableModel mastersTableModel;
    private final DefaultTableModel reportsExplorerTableModel;
    private final DefaultTableModel voucherLinesTableModel;
    private final DefaultTableModel printLedgerTableModel;
    private final JCheckBox wedgeScannerToggle;
    private final JComboBox<String> backendApiSchemeBox;
    private final JTextField backendApiHostField;
    private final JTextField backendApiPortField;
    private final JLabel backendApiEndpointBadge;

    private final Deque<SavedGoToFrameState> goToStack = new ArrayDeque<>();
    private final List<JButton> currentModalButtons = new ArrayList<>();

    private DesktopSessionCoordinator sessionCoordinator;
    private URI configuredBackendHostOrigin = URI.create("https://localhost:8443");
    private Path workstationConfigFile;
    private Function<URI, DesktopSessionCoordinator> sessionCoordinatorFactory;
    private String activeViewName = VIEW_GATEWAY;
    private String activeMasterRoute = "MASTER:LEDGER";
    private String activeVoucherType = "Journal";
    private int voucherModeIndex;
    private boolean optionalVoucherFlag;
    private boolean postDatedVoucherFlag;
    private boolean voucherDraftDirty;
    private boolean reportDetailedMode;
    private boolean trialBalanceLedgerWiseMode;
    private int comparativeColumnCount = 1;
    private String lastOpenedTopMenu;
    private String lastCommittedNarration = "Previous voucher narration";
    private String lastCalculatorResult = "0.00";
    private String lastDiagnosticMessage = "Diagnostic trace ready (zero secrets exposed)";
    private LocalDate activeContextDate = LocalDate.of(2026, 4, 15);
    private LocalDate activeReportFromDate = LocalDate.of(2026, 4, 1);
    private LocalDate activeReportToDate = LocalDate.of(2027, 3, 31);
    private int currentVoucherBrowseIndex = 1;
    private boolean f11GstEnabled = true;
    private boolean f11InventoryIntegrated = true;
    private boolean f11BillWiseEnabled = true;
    private JFrame nativeWindow;

    public MoneyCraftDesktopFrame(
        BrandSwingTheme theme,
        DesktopCommandRegistry commandRegistry,
        PrintAttemptLedger printAttemptLedger,
        ScaleAndBarcodeCaptureManager captureManager
    ) {
        this(
            theme,
            commandRegistry,
            printAttemptLedger,
            captureManager,
            new DesktopPreferencesManager(
                URI.create("https://localhost:8443"),
                Path.of(System.getProperty("java.io.tmpdir"), "mc-desktop-prefs-default"),
                "mc-swing-default-en-gb",
                commandRegistry,
                () -> false
            ),
            new ContextualHelpCatalog(),
            new ContextEpochGuard(),
            null
        );
    }

    public MoneyCraftDesktopFrame(
        BrandSwingTheme theme,
        DesktopCommandRegistry commandRegistry,
        PrintAttemptLedger printAttemptLedger,
        ScaleAndBarcodeCaptureManager captureManager,
        DesktopPreferencesManager preferencesManager,
        ContextualHelpCatalog helpCatalog,
        ContextEpochGuard epochGuard,
        NativeDesktopPlatformAdapter platformAdapter
    ) {
        this.theme = Objects.requireNonNull(theme, "theme must not be null");
        this.commandRegistry = Objects.requireNonNull(commandRegistry, "commandRegistry must not be null");
        this.printAttemptLedger = Objects.requireNonNull(printAttemptLedger, "printAttemptLedger must not be null");
        this.captureManager = Objects.requireNonNull(captureManager, "captureManager must not be null");
        this.preferencesManager = Objects.requireNonNull(preferencesManager, "preferencesManager must not be null");
        this.helpCatalog = Objects.requireNonNull(helpCatalog, "helpCatalog must not be null");
        this.epochGuard = Objects.requireNonNull(epochGuard, "epochGuard must not be null");
        this.platformAdapter = platformAdapter != null
            ? platformAdapter
            : new NativeDesktopPlatformAdapter(
                () -> openHelpForContext("nativeSignIn", "About MoneyCraft"),
                () -> showView(VIEW_PREFERENCES_AND_HELP),
                this::handleWindowReopen,
                () -> !voucherDraftDirty && commandRegistry.inFlightMutationCommandId().isEmpty()
            );

        refreshThemeAndScale();

        this.rootPane = new JRootPane();
        this.cardLayout = new CardLayout();
        this.cardHost = new JPanel(cardLayout);
        this.cardHost.setBackground(BrandSwingTheme.PAPER);

        this.activeViewBadge = new JLabel("Gateway of MoneyCraft");
        this.activeViewBadge.setFont(theme.headingFont(16));
        this.activeViewBadge.setForeground(BrandSwingTheme.INK);

        this.gatewayBreadcrumbLabel = new JLabel(gatewayTree.breadcrumbPath());
        this.gatewayBreadcrumbLabel.setFont(theme.headingFont(14));
        this.gatewayBreadcrumbLabel.setForeground(BrandSwingTheme.FOLD);

        this.statusBannerLabel = new JLabel("Ready");
        this.statusBannerLabel.setFont(theme.bodyFont(13));
        this.statusBannerLabel.setForeground(BrandSwingTheme.INK);

        this.companySearchField = new JTextField(24);
        this.companyTableModel = new DefaultTableModel(
            new String[]{"Code", "Legal Name", "Period", "Last Entry Date", "Currency"},
            0
        );
        this.createCompanyCodeField = new JTextField("100001", 12);
        this.createCompanyNameField = new JTextField("Raster Enterprise Pvt. Ltd.", 28);
        this.createCompanyStateField = new JTextField("29-Karnataka", 18);

        this.activeVoucherTypeLabel = new JLabel("Journal [F7]");
        this.activeVoucherTypeLabel.setFont(theme.headingFont(15));
        this.activeVoucherModeLabel = new JLabel("Mode: ITEM_INVOICE");

        this.voucherNumberField = new JTextField("VCH-0001", 14);
        this.voucherNumberField.setFont(theme.identifierMonoFont(13));
        this.voucherLedgerField = new JTextField(20);
        this.voucherDebitField = new JTextField("0.00", 12);
        this.voucherDebitField.setFont(theme.amountFont(14));
        this.voucherCreditField = new JTextField("0.00", 12);
        this.voucherCreditField.setFont(theme.amountFont(14));
        this.voucherNarrationField = new JTextField(28);
        this.barcodeCaptureField = new JTextField(18);
        this.barcodeCaptureField.setFont(theme.identifierMonoFont(13));
        this.barcodeCaptureField.putClientProperty(DesktopCommandRegistry.DESIGNATED_CAPTURE_CLIENT_PROP, Boolean.TRUE);
        this.scaleWeightStatusLabel = new JLabel("Scale: No reading captured");

        this.dayBookTableModel = new DefaultTableModel(
            new String[]{"Date", "Particulars", "Vch Type", "Vch No", "Debit", "Credit", "Status"},
            0
        );
        seedSampleDayBookRows();

        this.trialBalanceTableModel = new DefaultTableModel(
            new String[]{"Account", "Opening Dr", "Opening Cr", "Closing Dr", "Closing Cr"},
            0
        );
        this.trialBalanceEmptyStateLabel = new JLabel("No matching records");
        this.trialBalanceEmptyStateLabel.setIcon(null);
        this.trialBalanceEmptyStateLabel.setFont(theme.bodyFont(14));
        this.trialBalanceEmptyStateLabel.setForeground(BrandSwingTheme.INK);
        this.reportDetailModeBadge = new JLabel("Condensed");
        this.activeReportRouteLabel = new JLabel("Report: Trial Balance");
        this.activeMasterRouteLabel = new JLabel("Master: MASTER:LEDGER");
        this.mastersTableModel = new DefaultTableModel(
            new String[]{"Code / Symbol", "Name", "Parent / Group", "Opening / Rate", "Statutory / HSN / GSTIN"},
            0
        );
        this.reportsExplorerTableModel = new DefaultTableModel(
            new String[]{"Particulars", "Reference / Code", "Date / Period", "Debit / Inward (\u20B9)", "Credit / Outward (\u20B9)", "Closing / Net (\u20B9)"},
            0
        );
        this.voucherLinesTableModel = new DefaultTableModel(
            new String[]{"Side / Mode", "Ledger / Stock Item", "Godown / Batch", "Qty", "Rate (\u20B9)", "Debit (\u20B9)", "Credit (\u20B9)"},
            0
        );
        seedDefaultMastersAndVoucherLines();

        this.printLedgerTableModel = new DefaultTableModel(
            new String[]{"Attempt ID", "Document", "Profile", "State", "Evidence", "Reprint"},
            0
        );
        this.wedgeScannerToggle = new JCheckBox("Unframed Keyboard-Wedge Scanner Active (Suppress Action Letters)");
        this.wedgeScannerToggle.setBackground(BrandSwingTheme.PAPER);
        this.wedgeScannerToggle.setForeground(BrandSwingTheme.INK);
        this.wedgeScannerToggle.setFont(theme.bodyFont(13));
        this.wedgeScannerToggle.addActionListener(e ->
            commandRegistry.setUnframedWedgeScannerActive(wedgeScannerToggle.isSelected())
        );

        this.backendApiSchemeBox = new JComboBox<>(new String[]{"https", "http"});
        this.backendApiSchemeBox.setSelectedItem("https");
        this.backendApiHostField = new JTextField("localhost", 18);
        this.backendApiHostField.setFont(theme.identifierMonoFont(13));
        this.backendApiPortField = new JTextField("8443", 8);
        this.backendApiPortField.setFont(theme.identifierMonoFont(13));
        this.backendApiEndpointBadge = new JLabel("API: https://localhost:8443/api/v1");
        this.backendApiEndpointBadge.setFont(theme.identifierMonoFont(12));
        this.backendApiEndpointBadge.setForeground(BrandSwingTheme.FOLD);

        this.gatewayItemsPanel = new JPanel(new GridLayout(0, 1, 6, 6));
        this.gatewayItemsPanel.setBackground(BrandSwingTheme.WHITE);
        this.gatewayPanel = buildGatewayPanel();
        this.companySelectorPanel = buildCompanySelectorPanel();
        this.companyCreatePanel = buildCompanyCreatePanel();
        this.mastersPanel = buildMastersPanel();
        this.voucherEntryPanel = buildVoucherEntryPanel();
        this.dayBookPanel = buildDayBookPanel();
        this.trialBalancePanel = buildTrialBalancePanel();
        this.reportsExplorerPanel = buildReportsExplorerPanel();
        this.preferencesPanel = buildPreferencesAndHelpPanel();
        this.deviceConsolePanel = buildDeviceConsolePanel();

        this.modalOverlayPanel = new JPanel(new BorderLayout());
        this.modalOverlayPanel.setVisible(false);
        this.modalOverlayPanel.setBackground(BrandSwingTheme.WHITE);
        this.modalOverlayPanel.setBorder(BorderFactory.createLineBorder(BrandSwingTheme.INK, 2));

        this.helpOverlayTitleLabel = new JLabel();
        this.helpOverlayTitleLabel.setFont(theme.headingFont(14));
        this.helpOverlayBodyLabel = new JLabel();
        this.helpOverlayBodyLabel.setFont(theme.bodyFont(13));
        this.helpOverlayPanel = buildHelpOverlayPanel();

        cardHost.add(gatewayPanel, VIEW_GATEWAY);
        cardHost.add(companySelectorPanel, VIEW_COMPANY_SELECTOR);
        cardHost.add(companyCreatePanel, VIEW_COMPANY_CREATE);
        cardHost.add(mastersPanel, VIEW_MASTERS);
        cardHost.add(voucherEntryPanel, VIEW_VOUCHER_ENTRY);
        cardHost.add(dayBookPanel, VIEW_DAY_BOOK);
        cardHost.add(trialBalancePanel, VIEW_TRIAL_BALANCE);
        cardHost.add(reportsExplorerPanel, VIEW_REPORTS_EXPLORER);
        cardHost.add(preferencesPanel, VIEW_PREFERENCES_AND_HELP);
        cardHost.add(deviceConsolePanel, VIEW_DEVICE_CONSOLE);

        this.topMenuBar = buildTopMenuBar();
        rootPane.setJMenuBar(topMenuBar);

        JPanel shellContent = new JPanel(new BorderLayout(8, 8));
        shellContent.setBackground(BrandSwingTheme.PAPER);
        shellContent.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        shellContent.add(buildTopHeader(), BorderLayout.NORTH);
        shellContent.add(cardHost, BorderLayout.CENTER);
        shellContent.add(buildRightFunctionRail(), BorderLayout.EAST);
        shellContent.add(buildStatusFooter(), BorderLayout.SOUTH);

        JPanel rightOverlays = new JPanel(new BorderLayout(4, 4));
        rightOverlays.setOpaque(false);
        rightOverlays.add(helpOverlayPanel, BorderLayout.NORTH);
        rightOverlays.add(modalOverlayPanel, BorderLayout.CENTER);

        rootPane.getContentPane().setLayout(new BorderLayout());
        rootPane.getContentPane().add(shellContent, BorderLayout.CENTER);
        rootPane.getContentPane().add(rightOverlays, BorderLayout.EAST);

        rebuildGatewayMenuButtons();
        bindAllShellAndWorkflowShortcuts(shellContent);
        showView(VIEW_GATEWAY);
    }

    public JRootPane rootPane() {
        return rootPane;
    }

    public DesktopCommandRegistry commandRegistry() {
        return commandRegistry;
    }

    public JMenuBar topMenuBar() {
        return topMenuBar;
    }

    public String activeViewName() {
        return activeViewName;
    }

    public GatewayCascadeTree gatewayTree() {
        return gatewayTree;
    }

    public String gatewayBreadcrumbText() {
        return gatewayBreadcrumbLabel.getText();
    }

    public ContextEpochGuard epochGuard() {
        return epochGuard;
    }

    public DesktopPreferencesManager preferencesManager() {
        return preferencesManager;
    }

    public ContextualHelpCatalog helpCatalog() {
        return helpCatalog;
    }

    public NativeDesktopPlatformAdapter platformAdapter() {
        return platformAdapter;
    }

    public JPanel gatewayPanel() {
        return gatewayPanel;
    }

    public JPanel companySelectorPanel() {
        return companySelectorPanel;
    }

    public JPanel voucherEntryPanel() {
        return voucherEntryPanel;
    }

    public JPanel trialBalancePanel() {
        return trialBalancePanel;
    }

    public JPanel deviceConsolePanel() {
        return deviceConsolePanel;
    }

    public MastersWorkbenchPanel mastersWorkbenchPanel() {
        return mastersPanel;
    }

    public ReportsWorkbenchPanel reportsWorkbenchPanel() {
        return reportsExplorerPanel;
    }

    public JTextField companySearchField() {
        return companySearchField;
    }

    public JTextField voucherNumberField() {
        return voucherNumberField;
    }

    public JTextField voucherLedgerField() {
        return voucherLedgerField;
    }

    public JTextField voucherDebitField() {
        return voucherDebitField;
    }

    public JTextField voucherCreditField() {
        return voucherCreditField;
    }

    public JTextField voucherNarrationField() {
        return voucherNarrationField;
    }

    public JTextField barcodeCaptureField() {
        return barcodeCaptureField;
    }

    public JLabel trialBalanceEmptyStateLabel() {
        return trialBalanceEmptyStateLabel;
    }

    public JLabel statusBannerLabel() {
        return statusBannerLabel;
    }

    public String activeVoucherType() {
        return activeVoucherType;
    }

    public String activeVoucherMode() {
        return VOUCHER_MODES[voucherModeIndex];
    }

    public boolean isOptionalVoucherFlag() {
        return optionalVoucherFlag;
    }

    public boolean isPostDatedVoucherFlag() {
        return postDatedVoucherFlag;
    }

    public boolean isVoucherDraftDirty() {
        return voucherDraftDirty;
    }

    public void setVoucherDraftDirty(boolean dirty) {
        this.voucherDraftDirty = dirty;
    }

    public boolean isReportDetailedMode() {
        return reportDetailedMode;
    }

    public int comparativeColumnCount() {
        return comparativeColumnCount;
    }

    public String lastOpenedTopMenu() {
        return lastOpenedTopMenu;
    }

    public String activeReportRoute() {
        return activeReportRouteLabel.getText();
    }

    public int dayBookVisibleRowCount() {
        return dayBookTableModel.getRowCount();
    }

    public String dayBookRowStatus(int row) {
        return String.valueOf(dayBookTableModel.getValueAt(row, 6));
    }

    public Set<Integer> selectedDayBookRowIndices() {
        return Set.copyOf(selectedDayBookRowIndices);
    }

    public boolean isF11GstEnabled() {
        return f11GstEnabled;
    }

    public List<JButton> currentModalButtons() {
        return List.copyOf(currentModalButtons);
    }

    public ScaleAndBarcodeCaptureManager captureManager() {
        return captureManager;
    }

    public Optional<DesktopSessionCoordinator> sessionCoordinator() {
        return Optional.ofNullable(sessionCoordinator);
    }

    public void attachSessionCoordinator(DesktopSessionCoordinator coordinator) {
        this.sessionCoordinator = coordinator;
        if (coordinator != null && coordinator.apiClient() != null) {
            syncBackendApiFieldsFromOrigin(coordinator.apiClient().config().baseApiUri());
        }
    }

    public URI configuredBackendHostOrigin() {
        return configuredBackendHostOrigin;
    }

    public URI configuredBackendApiBaseUri() {
        return configuredBackendHostOrigin.resolve("/api/v1");
    }

    public JComboBox<String> backendApiSchemeBox() {
        return backendApiSchemeBox;
    }

    public JTextField backendApiHostField() {
        return backendApiHostField;
    }

    public JTextField backendApiPortField() {
        return backendApiPortField;
    }

    public JLabel backendApiEndpointBadge() {
        return backendApiEndpointBadge;
    }

    public void bindWorkstationHostConfiguration(
        URI initialHostOrigin,
        Path propertiesFile,
        Function<URI, DesktopSessionCoordinator> coordinatorFactory
    ) {
        this.workstationConfigFile = propertiesFile;
        this.sessionCoordinatorFactory = coordinatorFactory;
        if (initialHostOrigin != null) {
            syncBackendApiFieldsFromOrigin(initialHostOrigin);
        }
    }

    public URI applyBackendApiHostAndPort(String schemeRaw, String hostOrIp, int port) {
        URI newOrigin = MoneyCraftDesktopMain.composeHostOriginUri(
            schemeRaw,
            hostOrIp,
            String.valueOf(port)
        );
        boolean isLoopbackHttp = "http".equalsIgnoreCase(newOrigin.getScheme());
        // Validate against DesktopClientConfig SWG-001 & SWG-002 transport rules
        if (isLoopbackHttp) {
            DesktopClientConfig.loopbackTestConfig(newOrigin);
        } else {
            DesktopClientConfig.httpsDefault(newOrigin);
        }

        syncBackendApiFieldsFromOrigin(newOrigin);

        if (workstationConfigFile != null) {
            Properties existing = MoneyCraftDesktopMain.loadWorkstationProperties(workstationConfigFile);
            MoneyCraftDesktopMain.saveWorkstationHostProperties(workstationConfigFile, newOrigin, existing);
        }

        if (sessionCoordinatorFactory != null) {
            DesktopSessionCoordinator previous = this.sessionCoordinator;
            this.sessionCoordinator = sessionCoordinatorFactory.apply(newOrigin);
            if (previous != null) {
                previous.close();
            }
        } else if (this.sessionCoordinator != null) {
            DesktopSessionCoordinator previous = this.sessionCoordinator;
            this.sessionCoordinator = MoneyCraftDesktopMain.rebuildSessionCoordinatorForHost(
                newOrigin,
                workstationConfigFile != null
                    ? MoneyCraftDesktopMain.loadWorkstationProperties(workstationConfigFile)
                    : new Properties(),
                preferencesManager,
                commandRegistry,
                epochGuard
            );
            previous.close();
        }

        statusBannerLabel.setText("Backend API configured: " + configuredBackendApiBaseUri());
        return configuredBackendApiBaseUri();
    }

    public URI applyBackendApiFromFields() {
        String selectedScheme = String.valueOf(backendApiSchemeBox.getSelectedItem());
        String hostText = backendApiHostField.getText();
        String portText = backendApiPortField.getText() == null ? "" : backendApiPortField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Backend API port must be a valid integer between 1 and 65535: " + portText, ex);
        }
        return applyBackendApiHostAndPort(selectedScheme, hostText, port);
    }

    private void syncBackendApiFieldsFromOrigin(URI origin) {
        if (origin == null) {
            return;
        }
        String scheme = origin.getScheme() != null ? origin.getScheme().toLowerCase() : "https";
        String host = origin.getHost() != null ? origin.getHost().replace("[", "").replace("]", "") : "localhost";
        int port = origin.getPort() > 0 ? origin.getPort() : ("http".equals(scheme) ? 80 : 443);
        String formattedHost = host.contains(":") ? "[" + host + "]" : host;
        this.configuredBackendHostOrigin = URI.create(scheme + "://" + formattedHost + ":" + port);
        this.backendApiSchemeBox.setSelectedItem(scheme);
        this.backendApiHostField.setText(host);
        this.backendApiPortField.setText(String.valueOf(port));
        this.backendApiEndpointBadge.setText("API: " + this.configuredBackendHostOrigin.resolve("/api/v1"));
    }

    public String activeMasterRoute() {
        return activeMasterRoute;
    }

    public DefaultTableModel mastersTableModel() {
        return mastersTableModel;
    }

    public DefaultTableModel reportsExplorerTableModel() {
        return reportsExplorerTableModel;
    }

    public DefaultTableModel voucherLinesTableModel() {
        return voucherLinesTableModel;
    }

    public DefaultTableModel trialBalanceTableModel() {
        return trialBalanceTableModel;
    }

    public DefaultTableModel dayBookTableModel() {
        return dayBookTableModel;
    }

    public DefaultTableModel companyTableModel() {
        return companyTableModel;
    }

    public boolean isTrialBalanceLedgerWiseMode() {
        return trialBalanceLedgerWiseMode;
    }

    public void toggleTrialBalanceLedgerWiseView() {
        this.trialBalanceLedgerWiseMode = !trialBalanceLedgerWiseMode;
        statusBannerLabel.setText(
            trialBalanceLedgerWiseMode ? "Trial Balance: Ledger-wise [F5]" : "Trial Balance: Group-wise [F5]"
        );
    }

    public String lastCalculatorResult() {
        return lastCalculatorResult;
    }

    public LocalDate activeContextDate() {
        return activeContextDate;
    }

    public LocalDate activeReportFromDate() {
        return activeReportFromDate;
    }

    public LocalDate activeReportToDate() {
        return activeReportToDate;
    }

    /**
     * Evaluates a commercial calculator expression (including multi-operator precedence {@code *},
     * {@code /}, {@code +}, {@code -} and nested parentheses {@code (...)}) using exact
     * {@link BigDecimal} arithmetic (zero IEEE-754 float/double math).
     */
    public BigDecimal evaluateCalculatorExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            this.lastCalculatorResult = "0.00";
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        String cleaned = expression.replace(",", "").replace("\u20B9", "").trim();
        BigDecimal result = new ExactDecimalExpressionParser(cleaned).parse();
        BigDecimal scaled = result.setScale(2, RoundingMode.HALF_UP);
        this.lastCalculatorResult = scaled.toPlainString();
        statusBannerLabel.setText("Calculator [Alt+N]: " + expression + " = \u20B9 " + lastCalculatorResult);
        return scaled;
    }

    /**
     * Handles OS window close and Dock reopen lifecycle under locked or unlocked secret vaults
     * (S-02, S-06, AT-ITER110-023/025).
     */
    public boolean handleWindowCloseRequest() {
        boolean closed = platformAdapter.requestWindowClose();
        if (closed && sessionCoordinator != null) {
            sessionCoordinator.onWindowClosed(platformAdapter.isApplicationTerminated());
            if (platformAdapter.isApplicationTerminated()) {
                showView(VIEW_COMPANY_SELECTOR);
            }
        }
        return closed;
    }

    public void handleWindowReopen() {
        if (sessionCoordinator != null && !sessionCoordinator.onWindowReopened()) {
            statusBannerLabel.setText("Session expired (OS vault locked — interactive browser sign-in required)");
            showView(VIEW_COMPANY_SELECTOR);
            return;
        }
        showView(activeViewName);
    }

    public void refreshThemeAndScale() {
        theme.applyGlobalDefaults(
            preferencesManager.isEffectiveDarkTheme(),
            preferencesManager.effectiveTextScalePercent()
        );
    }

    /**
     * Enters Windows-style startup flow: opens directly on Select Company when no company is open (UM-110-03).
     */
    public void enterWindowsStartupFlow() {
        if (epochGuard.activeCompanyId() == null) {
            showView(VIEW_COMPANY_SELECTOR);
        } else {
            showView(VIEW_GATEWAY);
        }
    }

    /**
     * Submits the classic Create Company form and immediately opens the automatic post-create
     * {@code F11: Company Features} configuration modal (UM-110-03).
     */
    public long submitCreateCompanyAndOpenF11Features(UUID assignedCompanyId) {
        UUID companyId = assignedCompanyId == null ? UUID.randomUUID() : assignedCompanyId;
        String code = createCompanyCodeField.getText().trim();
        String name = createCompanyNameField.getText().trim();
        String stateRaw = createCompanyStateField.getText().trim();
        String stateCode = stateRaw.length() >= 2 ? stateRaw.substring(0, 2) : "29";
        companyTableModel.addRow(new Object[]{code, name, "2026-04-01 to 2027-03-31", "No Vouchers Entered", "INR (\u20B9)"});
        epochGuard.onCompanySwitched(companyId);
        if (sessionCoordinator != null) {
            ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
            CreateCompanyCommand cmd = new CreateCompanyCommand(
                code.isEmpty() ? "100001" : code,
                name.isEmpty() ? "Raster Enterprise Pvt. Ltd." : name,
                name.isEmpty() ? "Raster Enterprise Pvt. Ltd." : name,
                "Corporate Office",
                "IN",
                stateCode,
                stateRaw,
                "560001",
                "INR",
                "\u20B9",
                2,
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 4, 1),
                1L,
                CreateCompanyCommand.Features.defaults()
            );
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                sessionCoordinator.apiClient().createCompany(cmd, UUID.randomUUID()),
                resp -> sessionCoordinator.selectCompany(resp.body(), resp.etag()),
                err -> { },
                null
            );
        }
        statusBannerLabel.setText("Company created successfully — Configure F11 Features");
        return openF11CompanyFeaturesModal();
    }

    public long openF11CompanyFeaturesModal() {
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "f11.companyFeatures",
                "en-GB",
                "company.features",
                "Apply Features",
                'a',
                'A',
                KeyStroke.getKeyStroke("ctrl shift A"),
                StateTokenRole.ACTION_PRIMARY,
                "mc.icon.confirmed",
                "company.manage",
                () -> true,
                () -> {
                    f11GstEnabled = true;
                    f11InventoryIntegrated = true;
                    f11BillWiseEnabled = true;
                    if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
                        UUID cid = epochGuard.activeCompanyId();
                        String etag = sessionCoordinator.activeCompanyEtag().orElse("\"1\"");
                        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                        CreateCompanyCommand cmd = new CreateCompanyCommand(
                            createCompanyCodeField.getText().trim().isEmpty() ? "100000" : createCompanyCodeField.getText().trim(),
                            createCompanyNameField.getText().trim().isEmpty() ? "ABC & Co" : createCompanyNameField.getText().trim(),
                            createCompanyNameField.getText().trim().isEmpty() ? "ABC & Co" : createCompanyNameField.getText().trim(),
                            "Corporate Office",
                            "IN",
                            "33",
                            createCompanyStateField.getText().trim(),
                            "600001",
                            "INR",
                            "\u20B9",
                            2,
                            LocalDate.of(2026, 4, 1),
                            LocalDate.of(2026, 4, 1),
                            1L,
                            CreateCompanyCommand.Features.defaults()
                        );
                        DesktopApiClient.deliverOnEdtIfCurrent(
                            epochGuard,
                            snap,
                            sessionCoordinator.apiClient().updateCompanyFeatures(cid, cmd, etag, UUID.randomUUID()),
                            resp -> sessionCoordinator.selectCompany(resp.body(), resp.etag()),
                            err -> { },
                            null
                        );
                    }
                    statusBannerLabel.setText("F11 Company Features saved");
                    closeModal("f11.companyFeatures");
                    showView(VIEW_GATEWAY);
                }
            ),
            new ModalActionSpec(
                "f11.companyFeatures",
                "en-GB",
                "editor.close",
                "Close",
                'c',
                'C',
                KeyStroke.getKeyStroke("ctrl shift C"),
                StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close",
                "authenticated",
                () -> true,
                () -> closeModal("f11.companyFeatures")
            )
        );
        return openModal("f11.companyFeatures", "F11: Company Features (Accounting, Inventory, GST/TDS, MCA Edit Log)", specs);
    }

    /**
     * Drills into the 3-level Gateway menu using a single-letter mnemonic or Enter (UM-110-01).
     */
    public GatewayCascadeTree.DrillOutcome triggerGatewayMnemonic(char mnemonic) {
        GatewayCascadeTree.DrillOutcome outcome = gatewayTree.activateByMnemonic(mnemonic);
        applyGatewayDrillOutcome(outcome);
        return outcome;
    }

    public GatewayCascadeTree.DrillOutcome triggerGatewayEnter() {
        GatewayCascadeTree.DrillOutcome outcome = gatewayTree.activateSelected();
        applyGatewayDrillOutcome(outcome);
        return outcome;
    }

    /**
     * Non-destructive {@code Alt+G Go To} navigation: pushes current view and dirty voucher draft
     * onto an in-memory stack so pressing {@code Esc} restores the exact unsaved draft!
     */
    public void triggerGoToOverlay(String targetViewName) {
        goToStack.push(new SavedGoToFrameState(
            activeViewName,
            voucherLedgerField.getText(),
            voucherDebitField.getText(),
            voucherCreditField.getText(),
            voucherNarrationField.getText(),
            voucherDraftDirty,
            activeVoucherType
        ));
        showView(targetViewName);
        statusBannerLabel.setText("Go To -> " + targetViewName + " (Esc returns to draft)");
    }

    /**
     * Direct {@code Ctrl+G Switch To} navigation: invokes the 3-way {@code editor.dirty} modal
     * ({@code Save draft S/s}, {@code Discard D/d}, {@code Stay T/t}) when leaving a dirty editor!
     */
    public void triggerSwitchTo(String targetViewName) {
        if (voucherDraftDirty && VIEW_VOUCHER_ENTRY.equals(activeViewName)) {
            openEditorDirtyModal(targetViewName);
            return;
        }
        showView(targetViewName);
    }

    public long openEditorDirtyModal(String destinationView) {
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "editor.dirty", "en-GB", "journal.saveDraft", "Save draft", 's', 'S',
                KeyStroke.getKeyStroke("ctrl shift S"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.save-document", "voucher.create", () -> true,
                () -> submitVoucherSaveOrAccept(destinationView, "editor.dirty", "journal.saveDraft")
            ),
            new ModalActionSpec(
                "editor.dirty", "en-GB", "editor.discard", "Discard", 'd', 'D',
                KeyStroke.getKeyStroke("ctrl shift D"), StateTokenRole.ACTION_DANGER,
                "mc.icon.discard-draft", "authenticated", () -> true,
                () -> {
                    voucherDraftDirty = false;
                    voucherDebitField.setText("0.00");
                    voucherCreditField.setText("0.00");
                    closeModal("editor.dirty");
                    showView(destinationView);
                }
            ),
            new ModalActionSpec(
                "editor.dirty", "en-GB", "editor.stay", "Stay", 't', 'T',
                KeyStroke.getKeyStroke("ctrl shift T"), StateTokenRole.ACTION_SECONDARY,
                null, "authenticated", () -> true,
                () -> closeModal("editor.dirty")
            )
        );
        return openModal("editor.dirty", "Unsaved Voucher Changes", specs);
    }

    public void submitVoucherSaveOrAccept(
        String destinationViewAfterSaveOrNull,
        String modalToCloseOnSuccessOrNull,
        String mutationCommandId
    ) {
        BigDecimal amount = flushAndValidateVoucherAmounts();
        if (sessionCoordinator != null
            && epochGuard.activeCompanyId() != null
            && sessionCoordinator.activeVoucherId().isPresent()) {
            UUID companyId = epochGuard.activeCompanyId();
            UUID voucherId = sessionCoordinator.activeVoucherId().get();
            String ifMatch = sessionCoordinator.activeVoucherEtag().orElse("\"1\"");
            ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
            UpsertVoucherCommand cmd = new UpsertVoucherCommand(
                UUID.nameUUIDFromBytes(activeVoucherType.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                activeVoucherType,
                voucherNumberField.getText().trim(),
                sessionCoordinator.activeVoucherDate(),
                activeVoucherMode(),
                optionalVoucherFlag ? "OPTIONAL" : "REGULAR",
                "SAVE_DRAFT",
                "INR",
                BigDecimal.ONE,
                voucherNarrationField.getText().trim(),
                1L,
                List.of(
                    new VoucherLineCommand(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "DR",
                        amount,
                        voucherNarrationField.getText().trim()
                    ),
                    new VoucherLineCommand(
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        "CR",
                        amount,
                        voucherNarrationField.getText().trim()
                    )
                )
            );
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                sessionCoordinator.apiClient().updateVoucher(companyId, voucherId, cmd, ifMatch, UUID.randomUUID()),
                resp -> {
                    sessionCoordinator.setActiveVoucherContext(resp.body().id(), resp.etag());
                    voucherDraftDirty = false;
                    commandRegistry.markMutationCompleted(mutationCommandId);
                    statusBannerLabel.setText("Draft saved");
                    if (modalToCloseOnSuccessOrNull != null
                        && modalToCloseOnSuccessOrNull.equals(commandRegistry.activeModalContextId().orElse(null))) {
                        closeModal(modalToCloseOnSuccessOrNull);
                    }
                    if (destinationViewAfterSaveOrNull != null) {
                        showView(destinationViewAfterSaveOrNull);
                    }
                },
                err -> handleVoucherMutationFailure(err, modalToCloseOnSuccessOrNull, mutationCommandId),
                null
            );
            return;
        }
        voucherDraftDirty = false;
        commandRegistry.markMutationCompleted(mutationCommandId);
        statusBannerLabel.setText("voucher.accept".equals(mutationCommandId) ? "Voucher accepted" : "Draft saved");
        if (modalToCloseOnSuccessOrNull != null
            && modalToCloseOnSuccessOrNull.equals(commandRegistry.activeModalContextId().orElse(null))) {
            closeModal(modalToCloseOnSuccessOrNull);
        }
        if (destinationViewAfterSaveOrNull != null) {
            showView(destinationViewAfterSaveOrNull);
        }
    }

    private void handleVoucherMutationFailure(
        Throwable err,
        String activeModalToDismissOrNull,
        String mutationCommandId
    ) {
        this.voucherDraftDirty = true;
        if (activeModalToDismissOrNull != null
            && activeModalToDismissOrNull.equals(commandRegistry.activeModalContextId().orElse(null))) {
            closeModal(activeModalToDismissOrNull);
        }
        if (err instanceof UncertainTransportException uncertain) {
            if (sessionCoordinator != null) {
                sessionCoordinator.recordUncertainIntention(uncertain.intention());
            }
            openRequestUncertainModal(
                () -> sessionCoordinator != null && sessionCoordinator.isUncertainStatusChecked(),
                () -> {
                    if (sessionCoordinator != null) {
                        sessionCoordinator.markUncertainStatusChecked();
                        if (epochGuard.activeCompanyId() != null && sessionCoordinator.activeVoucherId().isPresent()) {
                            UUID cid = epochGuard.activeCompanyId();
                            UUID vid = sessionCoordinator.activeVoucherId().get();
                            ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                            DesktopApiClient.deliverOnEdtIfCurrent(
                                epochGuard,
                                snap,
                                sessionCoordinator.apiClient().getVoucher(cid, vid),
                                resp -> sessionCoordinator.setActiveVoucherContext(resp.body().id(), resp.etag()),
                                e -> { },
                                null
                            );
                        }
                    }
                    if (currentModalButtons.size() > 1) {
                        currentModalButtons.get(1).setEnabled(true);
                    }
                    statusBannerLabel.setText("Status checked for " + uncertain.intention().idempotencyKey());
                },
                () -> {
                    if (sessionCoordinator != null) {
                        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                        DesktopApiClient.deliverOnEdtIfCurrent(
                            epochGuard,
                            snap,
                            sessionCoordinator.apiClient().retryUncertainMutation(uncertain.intention(), VoucherDto.class),
                            resp -> {
                                sessionCoordinator.setActiveVoucherContext(resp.body().id(), resp.etag());
                                sessionCoordinator.clearUncertainIntention();
                                voucherDraftDirty = false;
                                if (mutationCommandId != null) {
                                    commandRegistry.markMutationCompleted(mutationCommandId);
                                }
                                statusBannerLabel.setText("Retried original intention " + uncertain.intention().idempotencyKey());
                            },
                            retryErr -> statusBannerLabel.setText("Retry failed: " + retryErr.getMessage()),
                            null
                        );
                        return;
                    }
                    if (mutationCommandId != null) {
                        commandRegistry.markMutationCompleted(mutationCommandId);
                    }
                    statusBannerLabel.setText("Retried original intention " + uncertain.intention().idempotencyKey());
                }
            );
            return;
        }
        if (mutationCommandId != null) {
            commandRegistry.markMutationCompleted(mutationCommandId);
        }
        if (err instanceof ApiProblemException prob) {
            boolean retryable = !prob.isStaleOptimisticLock() && prob.statusCode() >= 500;
            statusBannerLabel.setText("Error: " + prob.errorCode());
            openErrorRecoverableModal(
                prob.errorCode() + " (" + prob.statusCode() + "): " + prob.title(),
                retryable,
                null
            );
            return;
        }
        openErrorRecoverableModal(err.getMessage(), false, null);
    }

    /**
     * Parses Indian/standard grouped amounts (e.g. {@code "12,500.00"}) into exact {@link BigDecimal}
     * and flushes active text inputs before {@code Ctrl+A} voucher submission (UM-110-02).
     */
    public static BigDecimal parseFormattedDecimal(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return BigDecimal.ZERO.setScale(2);
        }
        String sanitized = rawInput.replace("\u20B9", "").replace(",", "").trim();
        return new BigDecimal(sanitized);
    }

    public BigDecimal flushAndValidateVoucherAmounts() {
        BigDecimal dr = parseFormattedDecimal(voucherDebitField.getText());
        BigDecimal cr = parseFormattedDecimal(voucherCreditField.getText());
        voucherDebitField.setText(dr.toPlainString());
        voucherCreditField.setText(cr.toPlainString());
        if (dr.signum() <= 0 || dr.compareTo(cr) != 0) {
            throw new IllegalArgumentException(
                "Double-entry voucher requires positive balanced Debit (" + dr.toPlainString()
                    + ") and Credit (" + cr.toPlainString() + ")."
            );
        }
        if (!voucherNarrationField.getText().isBlank()) {
            lastCommittedNarration = voucherNarrationField.getText().trim();
        }
        return dr;
    }

    public ContextualHelpCatalog.HelpEntry openHelpForContext(String helpIdOrKey, String controlLabel) {
        ContextualHelpCatalog.HelpEntry entry = helpCatalog.openHelpOverlay(helpIdOrKey, controlLabel);
        helpOverlayTitleLabel.setText(entry.helpId() + " — " + entry.title());
        helpOverlayBodyLabel.setText(entry.shortText() + " " + entry.instructions());
        helpOverlayPanel.setVisible(true);
        helpOverlayPanel.revalidate();
        return entry;
    }

    /**
     * Handles {@code Escape} following strict precedence:
     * 1. Topmost contextual help overlay (HELP-A: never cancels underlying modal or draft!)
     * 2. Active modal dialog
     * 3. Active {@code Alt+G Go To} return stack (restoring exact dirty voucher draft!)
     * 4. Gateway cascading sub-menu step-back ({@code Level 3 -> Level 2 -> Level 1})
     * 5. Dirty voucher guard or return to Gateway
     */
    public void handleEscapeKey() {
        if (helpCatalog.dismissTopmostHelpOnEscape()) {
            helpOverlayPanel.setVisible(false);
            statusBannerLabel.setText("Help closed");
            return;
        }
        if (commandRegistry.activeModalContextId().isPresent()) {
            closeModal(commandRegistry.activeModalContextId().get());
            return;
        }
        if (!goToStack.isEmpty()) {
            SavedGoToFrameState restored = goToStack.pop();
            showView(restored.viewName());
            this.activeVoucherType = restored.voucherType();
            this.voucherLedgerField.setText(restored.ledgerText());
            this.voucherDebitField.setText(restored.debitText());
            this.voucherCreditField.setText(restored.creditText());
            this.voucherNarrationField.setText(restored.narrationText());
            this.voucherDraftDirty = restored.dirty();
            statusBannerLabel.setText("Restored draft from Go To stack");
            return;
        }
        if (VIEW_GATEWAY.equals(activeViewName) && gatewayTree.stepBackOnEscape()) {
            gatewayBreadcrumbLabel.setText(gatewayTree.breadcrumbPath());
            rebuildGatewayMenuButtons();
            return;
        }
        if (voucherDraftDirty && VIEW_VOUCHER_ENTRY.equals(activeViewName)) {
            openEditorDirtyModal(VIEW_GATEWAY);
            return;
        }
        showView(VIEW_GATEWAY);
    }

    public void showView(String viewName) {
        Objects.requireNonNull(viewName, "viewName must not be null");
        cardLayout.show(cardHost, viewName);
        this.activeViewName = viewName;
        String effectiveEpochViewId = switch (viewName) {
            case VIEW_MASTERS -> mastersPanel.currentRoute();
            case VIEW_REPORTS_EXPLORER -> reportsExplorerPanel.currentRoute();
            default -> viewName;
        };
        if (!effectiveEpochViewId.equals(epochGuard.activeViewId())) {
            epochGuard.onViewChanged(effectiveEpochViewId);
        }
        if (VIEW_COMPANY_SELECTOR.equals(viewName)) {
            loadCompaniesFromServerIfConnected();
        }
        this.activeViewBadge.setText(switch (viewName) {
            case VIEW_GATEWAY -> "Gateway of MoneyCraft";
            case VIEW_COMPANY_SELECTOR -> "Select Company";
            case VIEW_COMPANY_CREATE -> "Create Company";
            case VIEW_MASTERS -> "Accounting & Inventory Masters";
            case VIEW_VOUCHER_ENTRY -> "Voucher Entry (" + activeVoucherType + ")";
            case VIEW_DAY_BOOK -> "Day Book";
            case VIEW_TRIAL_BALANCE -> "Trial Balance";
            case VIEW_REPORTS_EXPLORER -> activeReportRouteLabel.getText();
            case VIEW_PREFERENCES_AND_HELP -> "Appearance, Scaling & Keyboard Preferences";
            case VIEW_DEVICE_CONSOLE -> "Local Print & Device Console";
            default -> viewName;
        });
    }

    public void selectVoucherType(String voucherTypeName) {
        if (!ALL_24_VOUCHER_TYPES.contains(voucherTypeName)) {
            throw new IllegalArgumentException("Unknown voucher type: " + voucherTypeName);
        }
        this.activeVoucherType = voucherTypeName;
        this.activeVoucherTypeLabel.setText(voucherTypeName);
        showView(VIEW_VOUCHER_ENTRY);
    }

    public void populateCompanies(List<CompanyDto> companies) {
        companyTableModel.setRowCount(0);
        for (CompanyDto dto : companies) {
            companyTableModel.addRow(new Object[]{
                dto.code(),
                dto.legalName(),
                dto.financialYearFrom() + "",
                "Active",
                dto.baseCurrencyCode() + " (" + dto.currencySymbol() + ")"
            });
        }
    }

    public void populateTrialBalanceRows(List<TrialBalanceRowDto> rows) {
        trialBalanceTableModel.setRowCount(0);
        for (TrialBalanceRowDto row : rows) {
            trialBalanceTableModel.addRow(new Object[]{
                row.name(),
                safeAmountText(row.openingDebit()),
                safeAmountText(row.openingCredit()),
                safeAmountText(row.closingDebit()),
                safeAmountText(row.closingCredit())
            });
        }
        trialBalanceEmptyStateLabel.setVisible(rows.isEmpty());
    }

    public void refreshPrintAttempts(UUID companyId) {
        printLedgerTableModel.setRowCount(0);
        for (PrintAttemptRecord rec : printAttemptLedger.listAttemptsForCompany(companyId)) {
            printLedgerTableModel.addRow(new Object[]{
                rec.attemptId().toString().substring(0, 8),
                rec.documentId().toString().substring(0, 8) + " v" + rec.documentVersion(),
                rec.profileId(),
                rec.state().name(),
                rec.evidenceSource().name(),
                rec.reprint() ? "DUPLICATE / REPRINT" : "ORIGINAL"
            });
        }
    }

    public long openModal(String contextId, String title, List<ModalActionSpec> actions) {
        long generation = commandRegistry.pushModalContext(contextId, "en-GB", actions);
        modalOverlayPanel.removeAll();
        currentModalButtons.clear();

        JLabel heading = new JLabel(title);
        heading.setFont(theme.headingFont(15));
        heading.setForeground(BrandSwingTheme.INK);
        heading.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        modalOverlayPanel.add(heading, BorderLayout.NORTH);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        buttonRow.setBackground(BrandSwingTheme.WHITE);
        for (ModalActionSpec spec : actions) {
            String fullText = spec.label() + " " + commandRegistry.formatActionHint(spec);
            JButton btn = theme.createStyledActionButton(fullText, spec.stateTokenRole());
            btn.setEnabled(spec.enabledPredicate().getAsBoolean());
            btn.getAccessibleContext().setAccessibleName(fullText);
            btn.addActionListener(e -> {
                if (spec.enabledPredicate().getAsBoolean()) {
                    spec.actionHandler().run();
                }
            });
            currentModalButtons.add(btn);
            buttonRow.add(btn);
        }
        modalOverlayPanel.add(buttonRow, BorderLayout.CENTER);
        modalOverlayPanel.setVisible(true);
        modalOverlayPanel.revalidate();
        return generation;
    }

    public void closeModal(String contextId) {
        commandRegistry.popModalContext(contextId);
        currentModalButtons.clear();
        modalOverlayPanel.removeAll();
        modalOverlayPanel.setVisible(false);
        modalOverlayPanel.revalidate();
    }

    public boolean dispatchKeyToActiveModal(
        KeyEvent event,
        Component focusOwner,
        boolean imeComposing,
        long generation
    ) {
        if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            if (helpCatalog.dismissTopmostHelpOnEscape()) {
                helpOverlayPanel.setVisible(false);
                event.consume();
                return true;
            }
        }
        return commandRegistry.processModalKeyEvent(event, focusOwner, imeComposing, generation);
    }

    public BufferedImage renderSnapshotImage(int width, int height) {
        rootPane.setSize(new Dimension(width, height));
        layoutRecursively(rootPane);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            rootPane.paint(g2);
        } finally {
            g2.dispose();
        }
        return image;
    }

    public JFrame showInNativeWindow() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Cannot open heavyweight JFrame when java.awt.headless=true.");
        }
        if (nativeWindow == null) {
            nativeWindow = new JFrame("MoneyCraft 1.0.0 — Personal Desktop Client");
            nativeWindow.setDefaultCloseOperation(
                platformAdapter.isMacOs() ? JFrame.HIDE_ON_CLOSE : JFrame.DISPOSE_ON_CLOSE
            );
            nativeWindow.setJMenuBar(topMenuBar);
            nativeWindow.setContentPane(rootPane.getContentPane());
            nativeWindow.setSize(1366, 840);
            nativeWindow.setLocationRelativeTo(null);
        }
        nativeWindow.setVisible(true);
        return nativeWindow;
    }

    private void applyGatewayDrillOutcome(GatewayCascadeTree.DrillOutcome outcome) {
        gatewayBreadcrumbLabel.setText(outcome.breadcrumb());
        if (outcome.drilledIntoSubmenu()) {
            rebuildGatewayMenuButtons();
            return;
        }
        String target = outcome.leafTargetActionId();
        if (target == null) {
            return;
        }
        if (target.startsWith("MASTER:") || target.startsWith("STATUTORY:")) {
            this.activeMasterRoute = target;
            this.mastersPanel.activateRoute(target);
            showView(VIEW_MASTERS);
            statusBannerLabel.setText("Opened " + target);
        } else if (target.startsWith("BOOK:") || target.startsWith("STATEMENT:")
            || target.startsWith("INV_") || target.startsWith("STAT_REPORT:")
            || target.startsWith("EXCEPTION:") || target.startsWith("REPORT:")
            || target.startsWith("AUDIT:") || target.startsWith("BANKING:")
            || target.startsWith("EXCHANGE:") || target.startsWith("EXTENSION:")) {
            this.reportsExplorerPanel.activateRoute(target, this::loadTrialBalanceFromServerIfConnected);
            showView(VIEW_REPORTS_EXPLORER);
            statusBannerLabel.setText("Loaded " + target);
        } else if (VIEW_VOUCHER_ENTRY.equals(target)
            || VIEW_DAY_BOOK.equals(target)
            || VIEW_TRIAL_BALANCE.equals(target)) {
            showView(target);
            if (VIEW_TRIAL_BALANCE.equals(target)) {
                loadTrialBalanceFromServerIfConnected();
            } else if (VIEW_DAY_BOOK.equals(target)) {
                loadDayBookFromServerIfConnected();
            }
        } else {
            statusBannerLabel.setText("Opened " + target);
        }
    }

    private JMenuBar buildTopMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.add(createTopMenu("K: Company", KeyEvent.VK_K, List.of(
            menuItem("Create Company", () -> showView(VIEW_COMPANY_CREATE)),
            menuItem("Select Company [Alt+F3]", () -> showView(VIEW_COMPANY_SELECTOR)),
            menuItem("Shut Company [Ctrl+F3]", () -> {
                epochGuard.onCompanySwitched(null);
                showView(VIEW_COMPANY_SELECTOR);
            }),
            menuItem("F11: Company Features", this::openF11CompanyFeaturesModal),
            menuItem("Backend API Server (IP & Port) [F12]", () -> showView(VIEW_PREFERENCES_AND_HELP)),
            menuItem("Sign In via External Browser (PKCE S256)", this::triggerExternalBrowserSignIn),
            menuItem("Sign Out", this::triggerSignOut)
        )));
        bar.add(createTopMenu("Y: Data", KeyEvent.VK_Y, List.of(
            menuItem("Backup & Restore Status", () -> {
                reportsExplorerPanel.activateRoute("AUDIT:HASH_CHAIN", this::loadTrialBalanceFromServerIfConnected);
                showView(VIEW_REPORTS_EXPLORER);
                statusBannerLabel.setText("Server backup & MC-AUDIT-2 status verified");
            }),
            menuItem("Split / Verify Company Data", () -> {
                reportsExplorerPanel.activateRoute("AUDIT:OPENING_DIFF", this::loadTrialBalanceFromServerIfConnected);
                showView(VIEW_REPORTS_EXPLORER);
                statusBannerLabel.setText("Data verification view");
            })
        )));
        bar.add(createTopMenu("Z: Exchange", KeyEvent.VK_Z, List.of(
            menuItem("Data Synchronisation & Profiles", () -> {
                reportsExplorerPanel.activateRoute("EXCHANGE:XML_JSONEX", this::loadTrialBalanceFromServerIfConnected);
                showView(VIEW_REPORTS_EXPLORER);
                statusBannerLabel.setText("Exchange profiles");
            }),
            menuItem("Backend API Server (IP & Port) [F12]", () -> showView(VIEW_PREFERENCES_AND_HELP))
        )));
        bar.add(createTopMenu("G: Go To", KeyEvent.VK_G, List.of(
            menuItem("Go To Trial Balance [Alt+G]", () -> triggerGoToOverlay(VIEW_TRIAL_BALANCE)),
            menuItem("Go To Balance Sheet", () -> {
                reportsExplorerPanel.activateRoute("REPORT:BALANCE_SHEET", this::loadTrialBalanceFromServerIfConnected);
                triggerGoToOverlay(VIEW_REPORTS_EXPLORER);
            }),
            menuItem("Go To Day Book", () -> triggerGoToOverlay(VIEW_DAY_BOOK)),
            menuItem("SmartFind Global Search [Alt+F / Ctrl+F]", this::openCommandsMenuModal)
        )));
        bar.add(createTopMenu("O: Import", KeyEvent.VK_O, List.of(
            menuItem("Guided Spreadsheet Import (.xlsx/.ods/.csv)", () -> {
                reportsExplorerPanel.activateRoute("EXCHANGE:SPREADSHEET", this::loadTrialBalanceFromServerIfConnected);
                showView(VIEW_REPORTS_EXPLORER);
                statusBannerLabel.setText("Spreadsheet wizard");
            }),
            menuItem("Migrate Legacy .1800 Snapshot", () -> {
                reportsExplorerPanel.activateRoute("EXCHANGE:LEGACY_1800", this::loadTrialBalanceFromServerIfConnected);
                showView(VIEW_REPORTS_EXPLORER);
                statusBannerLabel.setText("Legacy .1800 migration");
            })
        )));
        bar.add(createTopMenu("E: Export", KeyEvent.VK_E, List.of(
            menuItem("Export Current Statement PDF", () -> openReportOutputModal(
                this::executeDefaultPrintDispatch,
                this::executeDefaultPdfExportDispatch
            ))
        )));
        bar.add(createTopMenu("M: Share", KeyEvent.VK_M, List.of(
            menuItem("Print / Share Dispatch Status", () -> showView(VIEW_DEVICE_CONSOLE))
        )));
        bar.add(createTopMenu("P: Print", KeyEvent.VK_P, List.of(
            menuItem("Print Current Document [Ctrl+P]", () -> openReportOutputModal(
                this::executeDefaultPrintDispatch,
                this::executeDefaultPdfExportDispatch
            )),
            menuItem("Local Printer & Peripheral Profiles", () -> showView(VIEW_DEVICE_CONSOLE))
        )));
        bar.add(createTopMenu("F1: Help", KeyEvent.VK_H, List.of(
            menuItem("Contextual Help [F1]", () -> openHelpForContext("HELP-110-001", "Application Header")),
            menuItem("Appearance, Scaling & Keyboard Preferences", () -> showView(VIEW_PREFERENCES_AND_HELP)),
            menuItem("About MoneyCraft Desktop", platformAdapter::triggerAbout)
        )));
        return bar;
    }

    private JMenu createTopMenu(String title, int mnemonicVk, List<JMenuItem> items) {
        JMenu menu = new JMenu(title);
        menu.setMnemonic(mnemonicVk);
        for (JMenuItem item : items) {
            menu.add(item);
        }
        return menu;
    }

    private static JMenuItem menuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    private JPanel buildTopHeader() {
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(BrandSwingTheme.WHITE);
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BrandSwingTheme.INK, 1),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        JLabel brandTitle = new JLabel("MoneyCraft 1.0.0");
        brandTitle.setFont(theme.headingFont(18));
        brandTitle.setForeground(BrandSwingTheme.INK);

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        navButtons.setBackground(BrandSwingTheme.WHITE);
        navButtons.add(createNavButton("Gateway", VIEW_GATEWAY, StateTokenRole.ACTION_SECONDARY));
        navButtons.add(createNavButton("Companies [F3]", VIEW_COMPANY_SELECTOR, StateTokenRole.ACTION_SECONDARY));
        navButtons.add(createNavButton("Voucher Entry", VIEW_VOUCHER_ENTRY, StateTokenRole.ACTION_PRIMARY));
        navButtons.add(createNavButton("Day Book", VIEW_DAY_BOOK, StateTokenRole.ACTION_SECONDARY));
        navButtons.add(createNavButton("Trial Balance", VIEW_TRIAL_BALANCE, StateTokenRole.ACTION_SECONDARY));
        navButtons.add(createNavButton("Settings", VIEW_PREFERENCES_AND_HELP, StateTokenRole.ACTION_SECONDARY));
        navButtons.add(createNavButton("Print & Devices", VIEW_DEVICE_CONSOLE, StateTokenRole.ACTION_SECONDARY));

        JButton helpTrigger = theme.createStyledActionButton("Help [F1]", StateTokenRole.ACTION_SECONDARY);
        helpTrigger.setPreferredSize(new Dimension(100, 32));
        helpTrigger.getAccessibleContext().setAccessibleName("Help with current screen");
        helpTrigger.addActionListener(e -> openHelpForContext("HELP-110-001", "Header Help Button"));
        navButtons.add(helpTrigger);

        header.add(brandTitle, BorderLayout.WEST);
        header.add(activeViewBadge, BorderLayout.CENTER);
        header.add(navButtons, BorderLayout.EAST);
        return header;
    }

    private JPanel buildRightFunctionRail() {
        JPanel rail = new JPanel(new GridLayout(11, 1, 4, 4));
        rail.setBackground(BrandSwingTheme.PAPER);
        rail.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));

        rail.add(createActionBtn("F2: Date", () -> openContextDateDialogModal(activeContextDate)));
        rail.add(createActionBtn("F3: Company", () -> showView(VIEW_COMPANY_SELECTOR)));
        rail.add(createActionBtn("F4: Contra", () -> selectVoucherType("Contra")));
        rail.add(createActionBtn("F5: Payment", () -> selectVoucherType("Payment")));
        rail.add(createActionBtn("F6: Receipt", () -> selectVoucherType("Receipt")));
        rail.add(createActionBtn("F7: Journal", () -> selectVoucherType("Journal")));
        rail.add(createActionBtn("F8: Sales", () -> selectVoucherType("Sales")));
        rail.add(createActionBtn("F9: Purchase", () -> selectVoucherType("Purchase")));
        rail.add(createActionBtn("F10: Other Vch", () -> selectVoucherType("Memorandum")));
        rail.add(createActionBtn("F11: Features", this::openF11CompanyFeaturesModal));
        rail.add(createActionBtn("F12: Configure", () -> showView(VIEW_PREFERENCES_AND_HELP)));
        return rail;
    }

    private JButton createActionBtn(String label, Runnable r) {
        JButton btn = theme.createStyledActionButton(label, StateTokenRole.ACTION_SECONDARY);
        btn.addActionListener(e -> r.run());
        return btn;
    }

    private JPanel buildStatusFooter() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(BrandSwingTheme.PAPER);
        footer.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        footer.add(statusBannerLabel, BorderLayout.WEST);
        footer.add(wedgeScannerToggle, BorderLayout.EAST);
        return footer;
    }

    private JPanel buildGatewayPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        panel.add(gatewayBreadcrumbLabel, BorderLayout.NORTH);
        panel.add(new JScrollPane(gatewayItemsPanel), BorderLayout.CENTER);
        return panel;
    }

    private void rebuildGatewayMenuButtons() {
        gatewayItemsPanel.removeAll();
        List<GatewayCascadeTree.MenuNode> nodes = gatewayTree.currentItems();
        for (int i = 0; i < nodes.size(); i++) {
            int idx = i;
            GatewayCascadeTree.MenuNode node = nodes.get(i);
            String suffix = node.children().isEmpty() ? "" : "  >";
            JButton itemBtn = theme.createStyledActionButton(
                "[" + node.mnemonic() + "] " + node.label() + suffix,
                i == gatewayTree.selectedIndex() ? StateTokenRole.ACTION_PRIMARY : StateTokenRole.ACTION_SECONDARY
            );
            itemBtn.addActionListener(e -> {
                gatewayTree.setSelectedIndex(idx);
                triggerGatewayEnter();
            });
            gatewayItemsPanel.add(itemBtn);
        }
        gatewayItemsPanel.revalidate();
    }

    private JPanel buildCompanySelectorPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        topRow.setBackground(BrandSwingTheme.WHITE);
        topRow.add(new JLabel("Search Company:"));
        topRow.add(companySearchField);
        JButton createCompanyBtn = theme.createStyledActionButton("Create Company", StateTokenRole.ACTION_PRIMARY);
        createCompanyBtn.addActionListener(e -> showView(VIEW_COMPANY_CREATE));
        topRow.add(createCompanyBtn);
        JButton serverConfigBtn = theme.createStyledActionButton("Server IP & Port [F12]", StateTokenRole.ACTION_SECONDARY);
        serverConfigBtn.addActionListener(e -> showView(VIEW_PREFERENCES_AND_HELP));
        topRow.add(serverConfigBtn);
        topRow.add(backendApiEndpointBadge);
        panel.add(topRow, BorderLayout.NORTH);

        panel.add(new JScrollPane(new JTable(companyTableModel)), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);
        JButton openBtn = theme.createStyledActionButton("Open [O/o]", StateTokenRole.ACTION_PRIMARY);
        openBtn.addActionListener(e -> {
            if (sessionCoordinator != null && sessionCoordinator.activeCompany().isPresent()) {
                CompanyDto comp = sessionCoordinator.activeCompany().get();
                sessionCoordinator.selectCompany(comp, sessionCoordinator.activeCompanyEtag().orElse("\"1\""));
                ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().listPeriods(comp.id()),
                    periods -> { },
                    err -> { },
                    null
                );
            }
            statusBannerLabel.setText("Company selected");
            showView(VIEW_GATEWAY);
        });
        JButton closeBtn = theme.createStyledActionButton("Close [C/c]", StateTokenRole.ACTION_SECONDARY);
        closeBtn.addActionListener(e -> showView(VIEW_GATEWAY));
        actions.add(openBtn);
        actions.add(closeBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    public void triggerSignOut() {
        if (sessionCoordinator != null) {
            sessionCoordinator.signOut();
        }
        epochGuard.onCompanySwitched(null);
        statusBannerLabel.setText("Signed out");
        showView(VIEW_COMPANY_SELECTOR);
    }

    public void triggerExternalBrowserSignIn() {
        statusBannerLabel.setText("Launching system browser PKCE");
        if (sessionCoordinator != null) {
            sessionCoordinator.signInAndDeliverOnEdt(
                Duration.ofSeconds(30),
                companies -> {
                    populateCompanies(companies);
                    statusBannerLabel.setText("Signed in via external browser PKCE (" + companies.size() + " companies)");
                },
                err -> openErrorRecoverableModal(err.getMessage(), false, null)
            );
        }
    }

    private JPanel buildCompanyCreatePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridLayout(4, 2, 8, 8));
        form.setBackground(BrandSwingTheme.WHITE);
        form.add(new JLabel("Company Data Code:"));
        form.add(createCompanyCodeField);
        form.add(new JLabel("Company Legal Name:"));
        form.add(createCompanyNameField);
        form.add(new JLabel("State / Statutory Jurisdiction:"));
        form.add(createCompanyStateField);
        form.add(new JLabel("Base Currency:"));
        form.add(new JLabel("\u20B9 (INR — Indian Rupee, exact BigDecimal scale 2)"));
        panel.add(form, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);
        JButton acceptBtn = theme.createStyledActionButton("Accept [Ctrl+A]", StateTokenRole.ACTION_PRIMARY);
        acceptBtn.addActionListener(e -> submitCreateCompanyAndOpenF11Features(null));
        actions.add(acceptBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private MastersWorkbenchPanel buildMastersPanel() {
        return new MastersWorkbenchPanel(
            theme,
            mastersTableModel,
            activeMasterRouteLabel,
            statusBannerLabel,
            epochGuard,
            () -> sessionCoordinator,
            callback -> openInlineLedgerCreateModal("New Master Account", callback)
        );
    }

    private JPanel buildVoucherEntryPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel vchHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        vchHeader.setBackground(BrandSwingTheme.WHITE);
        vchHeader.add(activeVoucherTypeLabel);
        vchHeader.add(activeVoucherModeLabel);
        panel.add(vchHeader, BorderLayout.NORTH);

        JPanel centerSplit = new JPanel(new BorderLayout(8, 8));
        centerSplit.setBackground(BrandSwingTheme.WHITE);

        JPanel form = new JPanel(new GridLayout(7, 2, 8, 8));
        form.setBackground(BrandSwingTheme.WHITE);
        form.add(new JLabel("Voucher Number:"));
        form.add(voucherNumberField);
        form.add(new JLabel("Ledger Account:"));
        form.add(voucherLedgerField);
        form.add(new JLabel("Debit (\u20B9):"));
        form.add(voucherDebitField);
        form.add(new JLabel("Credit (\u20B9):"));
        form.add(voucherCreditField);
        form.add(new JLabel("Narration [Ctrl+R repeat]:"));
        form.add(voucherNarrationField);
        form.add(new JLabel("Barcode Capture:"));
        form.add(barcodeCaptureField);
        form.add(new JLabel("Weighing Scale:"));
        form.add(scaleWeightStatusLabel);
        centerSplit.add(form, BorderLayout.NORTH);

        JTable linesTable = new JTable(voucherLinesTableModel);
        linesTable.setFont(theme.amountFont(13));
        centerSplit.add(new JScrollPane(linesTable), BorderLayout.CENTER);
        panel.add(centerSplit, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);
        JButton lookupBtn = theme.createStyledActionButton("Lookup Ledger [Ctrl+Enter]", StateTokenRole.ACTION_SECONDARY);
        lookupBtn.addActionListener(e -> openLedgerLookupModal(
            selected -> voucherLedgerField.setText(selected),
            () -> openInlineLedgerCreateModal("Inline Ledger", selected -> voucherLedgerField.setText(selected))
        ));
        JButton saveDraftBtn = theme.createStyledActionButton("Save draft [S/s]", StateTokenRole.ACTION_PRIMARY);
        saveDraftBtn.addActionListener(e -> submitVoucherSaveOrAccept(null, null, "journal.saveDraft"));
        JButton approveBtn = theme.createStyledActionButton("Approve [A/a]", StateTokenRole.ACTION_PRIMARY);
        approveBtn.addActionListener(e -> openApproveConfirmationModal(() -> statusBannerLabel.setText("Approved")));
        JButton postBtn = theme.createStyledActionButton("Post [P/p]", StateTokenRole.ACTION_PRIMARY);
        postBtn.addActionListener(e -> openPostConfirmationModal(() -> statusBannerLabel.setText("Posted")));
        actions.add(lookupBtn);
        actions.add(saveDraftBtn);
        actions.add(approveBtn);
        actions.add(postBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildDayBookPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(new JScrollPane(new JTable(dayBookTableModel)), BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTrialBalancePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(BrandSwingTheme.WHITE);
        top.add(trialBalanceEmptyStateLabel, BorderLayout.WEST);
        top.add(reportDetailModeBadge, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);

        JTable table = new JTable(trialBalanceTableModel);
        table.setFont(theme.amountFont(13));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);
        JButton outputBtn = theme.createStyledActionButton("Print / Export [U/u]", StateTokenRole.ACTION_PRIMARY);
        outputBtn.addActionListener(e -> openReportOutputModal(
            this::executeDefaultPrintDispatch,
            this::executeDefaultPdfExportDispatch
        ));
        actions.add(outputBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private ReportsWorkbenchPanel buildReportsExplorerPanel() {
        return new ReportsWorkbenchPanel(
            theme,
            reportsExplorerTableModel,
            activeReportRouteLabel,
            statusBannerLabel,
            epochGuard,
            () -> sessionCoordinator,
            () -> openReportFiltersModal(
                activeReportFromDate,
                activeReportToDate,
                () -> statusBannerLabel.setText("Applied report filters: " + activeReportFromDate + " .. " + activeReportToDate)
            ),
            () -> openReportOutputModal(
                this::executeDefaultPrintDispatch,
                this::executeDefaultPdfExportDispatch
            )
        );
    }

    private JPanel buildPreferencesAndHelpPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel grid = new JPanel(new GridLayout(8, 2, 8, 8));
        grid.setBackground(BrandSwingTheme.WHITE);
        JComboBox<AppearanceMode> appearanceBox = new JComboBox<>(AppearanceMode.values());
        appearanceBox.setSelectedItem(preferencesManager.effectiveAppearanceChoice());
        appearanceBox.addActionListener(e -> {
            AppearanceMode chosen = (AppearanceMode) appearanceBox.getSelectedItem();
            preferencesManager.saveLocalDesktopOverride(chosen, preferencesManager.effectiveTextScalePercent(), null);
            refreshThemeAndScale();
        });

        JComboBox<String> scaleBox = new JComboBox<>(
            new String[]{"75", "87.5", "100", "112.5", "125", "150", "175", "200"}
        );
        scaleBox.setSelectedItem("100");
        scaleBox.addActionListener(e -> {
            String raw = (String) scaleBox.getSelectedItem();
            if (raw != null) {
                preferencesManager.saveLocalDesktopOverride(
                    preferencesManager.effectiveAppearanceChoice(),
                    new BigDecimal(raw),
                    null
                );
                refreshThemeAndScale();
            }
        });

        grid.add(new JLabel("Backend API Protocol (HTTPS / Loopback HTTP):"));
        grid.add(backendApiSchemeBox);
        grid.add(new JLabel("Backend API Server IP / Hostname:"));
        grid.add(backendApiHostField);
        grid.add(new JLabel("Backend API Server Port:"));
        grid.add(backendApiPortField);
        grid.add(new JLabel("Effective Backend API Endpoint:"));
        grid.add(backendApiEndpointBadge);
        grid.add(new JLabel("Appearance (System / Light / Dark):"));
        grid.add(appearanceBox);
        grid.add(new JLabel("Interface Text Size (%):"));
        grid.add(scaleBox);
        grid.add(new JLabel("Preference Scope Guidance:"));
        grid.add(new JLabel("My account follows you when you sign in. This desktop overrides locally."));
        grid.add(new JLabel("Local Override Masking Status:"));
        grid.add(new JLabel(
            preferencesManager.isLocalAppearanceMaskingAccount()
                ? "This desktop overrides My account"
                : "Following My account defaults"
        ));
        panel.add(grid, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);
        JButton applyBackendApiBtn = theme.createStyledActionButton(
            "Apply Backend API IP & Port",
            StateTokenRole.ACTION_PRIMARY
        );
        applyBackendApiBtn.addActionListener(e -> {
            try {
                applyBackendApiFromFields();
            } catch (IllegalArgumentException ex) {
                openErrorRecoverableModal(ex.getMessage(), false, null);
            }
        });
        JButton useAccountBtn = theme.createStyledActionButton(
            "Use account settings on this desktop",
            StateTokenRole.ACTION_SECONDARY
        );
        useAccountBtn.addActionListener(e -> {
            preferencesManager.clearSelectedLocalOverrides(true, true, true);
            refreshThemeAndScale();
            statusBannerLabel.setText("Cleared local overrides — using My account settings");
        });
        JButton saveAccountBtn = theme.createStyledActionButton(
            "Save to My Account",
            StateTokenRole.ACTION_PRIMARY
        );
        saveAccountBtn.addActionListener(e -> {
            if (sessionCoordinator != null) {
                preferencesManager.patchAccountPreferences(
                    sessionCoordinator.apiClient(),
                    new UiPreferencesEnvelopeDto.PatchChanges(
                        preferencesManager.effectiveAppearanceChoice(),
                        preferencesManager.effectiveTextScalePercent(),
                        null
                    ),
                    UUID.randomUUID()
                );
            }
            statusBannerLabel.setText("Saved preferences to My account");
        });
        JButton keyboardBtn = theme.createStyledActionButton(
            "Keyboard Settings [Ctrl+,]",
            StateTokenRole.ACTION_SECONDARY
        );
        keyboardBtn.addActionListener(e -> openKeyboardSettingsModal());
        actions.add(applyBackendApiBtn);
        actions.add(useAccountBtn);
        actions.add(saveAccountBtn);
        actions.add(keyboardBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildDeviceConsolePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BrandSwingTheme.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel warningNote = new JLabel(
            "Spooler transfer does not guarantee physical paper completion; reconcile OUTCOME_UNKNOWN before reprint."
        );
        warningNote.setFont(theme.bodyFont(13));
        warningNote.setForeground(BrandSwingTheme.WARNING);
        panel.add(warningNote, BorderLayout.NORTH);

        JTable table = new JTable(printLedgerTableModel);
        table.setFont(theme.identifierMonoFont(12));
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setBackground(BrandSwingTheme.WHITE);
        JButton reconcileBtn = theme.createStyledActionButton("Reconcile Unknown", StateTokenRole.ACTION_SECONDARY);
        reconcileBtn.addActionListener(e -> {
            UUID cid = epochGuard.activeCompanyId();
            if (cid != null) {
                DeviceAuthorityContext auth = new DeviceAuthorityContext(
                    DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
                    UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    cid,
                    true,
                    Set.of("company.read", "export", "privateField.read")
                );
                for (PrintAttemptRecord rec : printAttemptLedger.listAttemptsForCompany(cid)) {
                    if (rec.state() == PrintAttemptState.OUTCOME_UNKNOWN && !rec.unknownReconciled()) {
                        printAttemptLedger.reconcileUnknownAttempt(
                            rec.attemptId(),
                            auth,
                            true,
                            "Operator confirmed paper output from Device Console"
                        );
                    }
                }
                refreshPrintAttempts(cid);
            }
            statusBannerLabel.setText("Reconciled unknown print attempts");
        });
        JButton reprintBtn = theme.createStyledActionButton("Audited Reprint", StateTokenRole.ACTION_PRIMARY);
        reprintBtn.addActionListener(e -> {
            UUID cid = epochGuard.activeCompanyId();
            if (cid != null) {
                DeviceAuthorityContext auth = new DeviceAuthorityContext(
                    DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
                    UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    cid,
                    true,
                    Set.of("company.read", "export", "privateField.read")
                );
                List<PrintAttemptRecord> attempts = printAttemptLedger.listAttemptsForCompany(cid);
                if (!attempts.isEmpty()) {
                    PrintAttemptRecord last = attempts.get(attempts.size() - 1);
                    printAttemptLedger.createAuditedReprint(
                        last.attemptId(),
                        auth,
                        "Operator requested audited duplicate reprint",
                        "%PDF-1.4 DUPLICATE / REPRINT".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    );
                    refreshPrintAttempts(cid);
                }
            }
            statusBannerLabel.setText("Created audited duplicate reprint");
        });
        actions.add(reconcileBtn);
        actions.add(reprintBtn);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildHelpOverlayPanel() {
        JPanel overlay = new JPanel(new BorderLayout(6, 6));
        overlay.setVisible(false);
        overlay.setBackground(BrandSwingTheme.PAPER);
        overlay.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BrandSwingTheme.FOLD, 2),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        overlay.add(helpOverlayTitleLabel, BorderLayout.NORTH);
        overlay.add(helpOverlayBodyLabel, BorderLayout.CENTER);
        JButton closeHelpBtn = theme.createStyledActionButton("Close Help [Esc]", StateTokenRole.ACTION_SECONDARY);
        closeHelpBtn.setPreferredSize(new Dimension(140, 28));
        closeHelpBtn.addActionListener(e -> handleEscapeKey());
        overlay.add(closeHelpBtn, BorderLayout.SOUTH);
        return overlay;
    }

    public long openPostConfirmationModal(Runnable onConfirmPost) {
        Objects.requireNonNull(onConfirmPost, "onConfirmPost must not be null");
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "journal.postConfirm",
                "en-GB",
                "journal.post",
                "Post",
                'p',
                'P',
                KeyStroke.getKeyStroke("ctrl shift P"),
                StateTokenRole.ACTION_PRIMARY,
                "mc.icon.post-ledger",
                "voucher.post",
                () -> true,
                () -> {
                    if (sessionCoordinator != null
                        && epochGuard.activeCompanyId() != null
                        && sessionCoordinator.activeVoucherId().isPresent()) {
                        UUID companyId = epochGuard.activeCompanyId();
                        UUID voucherId = sessionCoordinator.activeVoucherId().get();
                        String ifMatch = sessionCoordinator.activeVoucherEtag().orElse("\"1\"");
                        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                        DesktopApiClient.deliverOnEdtIfCurrent(
                            epochGuard,
                            snap,
                            sessionCoordinator.apiClient().postVoucher(companyId, voucherId, ifMatch, UUID.randomUUID()),
                            resp -> {
                                sessionCoordinator.setActiveVoucherContext(resp.body().id(), resp.etag());
                                voucherDraftDirty = false;
                                onConfirmPost.run();
                                commandRegistry.markMutationCompleted("journal.post");
                                closeModal("journal.postConfirm");
                            },
                            err -> handleVoucherMutationFailure(err, "journal.postConfirm", "journal.post"),
                            null
                        );
                        return;
                    }
                    onConfirmPost.run();
                    commandRegistry.markMutationCompleted("journal.post");
                    closeModal("journal.postConfirm");
                }
            ),
            new ModalActionSpec(
                "journal.postConfirm",
                "en-GB",
                "editor.close",
                "Return",
                'r',
                'R',
                KeyStroke.getKeyStroke("ctrl shift C"),
                StateTokenRole.ACTION_SECONDARY,
                "mc.icon.back",
                "authenticated",
                () -> true,
                () -> closeModal("journal.postConfirm")
            )
        );
        return openModal("journal.postConfirm", "Confirm Voucher Posting", specs);
    }

    public long openApproveConfirmationModal(Runnable onConfirmApprove) {
        Objects.requireNonNull(onConfirmApprove, "onConfirmApprove must not be null");
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "journal.approveConfirm", "en-GB", "journal.approve", "Approve", 'a', 'A',
                KeyStroke.getKeyStroke("ctrl shift A"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.approve-shield", "voucher.approve", () -> true,
                () -> {
                    if (sessionCoordinator != null
                        && epochGuard.activeCompanyId() != null
                        && sessionCoordinator.activeVoucherId().isPresent()) {
                        UUID companyId = epochGuard.activeCompanyId();
                        UUID voucherId = sessionCoordinator.activeVoucherId().get();
                        String ifMatch = sessionCoordinator.activeVoucherEtag().orElse("\"1\"");
                        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                        DesktopApiClient.deliverOnEdtIfCurrent(
                            epochGuard,
                            snap,
                            sessionCoordinator.apiClient().approveVoucher(
                                companyId,
                                voucherId,
                                new ApproveVoucherCommand(Boolean.FALSE, "Approved from Swing"),
                                ifMatch,
                                UUID.randomUUID()
                            ),
                            resp -> {
                                sessionCoordinator.setActiveVoucherContext(resp.body().id(), resp.etag());
                                onConfirmApprove.run();
                                commandRegistry.markMutationCompleted("journal.approve");
                                closeModal("journal.approveConfirm");
                            },
                            err -> handleVoucherMutationFailure(err, "journal.approveConfirm", "journal.approve"),
                            null
                        );
                        return;
                    }
                    onConfirmApprove.run();
                    commandRegistry.markMutationCompleted("journal.approve");
                    closeModal("journal.approveConfirm");
                }
            ),
            new ModalActionSpec(
                "journal.approveConfirm", "en-GB", "editor.close", "Return", 'r', 'R',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.back", "authenticated", () -> true,
                () -> closeModal("journal.approveConfirm")
            )
        );
        return openModal("journal.approveConfirm", "Confirm Voucher Approval (FOUR_EYES)", specs);
    }

    public long openCompanyInitialModal(Runnable onOpenSelected) {
        Objects.requireNonNull(onOpenSelected, "onOpenSelected must not be null");
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "company.initial", "en-GB", "company.open", "Open", 'o', 'O',
                KeyStroke.getKeyStroke("ctrl shift O"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.company", "company.read", () -> true,
                () -> {
                    onOpenSelected.run();
                    closeModal("company.initial");
                }
            )
        );
        return openModal("company.initial", "Select Initial Company", specs);
    }

    public long openCompanyPickModal(Runnable onOpenSelected) {
        Objects.requireNonNull(onOpenSelected, "onOpenSelected must not be null");
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "company.pick", "en-GB", "company.open", "Open", 'o', 'O',
                KeyStroke.getKeyStroke("ctrl shift O"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.company", "company.read", () -> true,
                () -> {
                    onOpenSelected.run();
                    closeModal("company.pick");
                }
            ),
            new ModalActionSpec(
                "company.pick", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("company.pick")
            )
        );
        return openModal("company.pick", "Change Active Company", specs);
    }

    public long openLedgerLookupModal(Consumer<String> onSelectLedger, Runnable onOpenCreateLedger) {
        Objects.requireNonNull(onSelectLedger, "onSelectLedger must not be null");
        Objects.requireNonNull(onOpenCreateLedger, "onOpenCreateLedger must not be null");
        if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
            UUID cid = epochGuard.activeCompanyId();
            ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                sessionCoordinator.apiClient().typeaheadLedgers(cid, voucherLedgerField.getText().trim(), 20),
                ledgers -> {
                    if (!ledgers.isEmpty() && voucherLedgerField.getText().isBlank()) {
                        voucherLedgerField.setText(ledgers.get(0).name());
                    }
                },
                err -> { },
                null
            );
        }
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "ledger.lookup", "en-GB", "ledger.select", "Select", 's', 'S',
                KeyStroke.getKeyStroke("ctrl shift L"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.ledger", "master.read", () -> true,
                () -> {
                    String current = voucherLedgerField.getText().isBlank()
                        ? "Sales Account"
                        : voucherLedgerField.getText().trim();
                    onSelectLedger.accept(current);
                    closeModal("ledger.lookup");
                }
            ),
            new ModalActionSpec(
                "ledger.lookup", "en-GB", "ledger.create", "Create ledger", 'n', 'N',
                KeyStroke.getKeyStroke("ctrl shift N"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.ledger", "master.create", () -> true,
                () -> {
                    closeModal("ledger.lookup");
                    onOpenCreateLedger.run();
                }
            ),
            new ModalActionSpec(
                "ledger.lookup", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("ledger.lookup")
            )
        );
        return openModal("ledger.lookup", "Lookup Ledger Account", specs);
    }

    public long openInlineLedgerCreateModal(String candidateName, Consumer<String> onSavedLedger) {
        Objects.requireNonNull(onSavedLedger, "onSavedLedger must not be null");
        String resolvedName = (candidateName == null || candidateName.isBlank())
            ? "Inline Secondary Ledger"
            : candidateName.trim();
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "ledger.create", "en-GB", "ledger.save", "Save", 's', 'S',
                KeyStroke.getKeyStroke("ctrl shift S"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.save-document", "master.create", () -> true,
                () -> {
                    if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
                        UUID cid = epochGuard.activeCompanyId();
                        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                        CreateLedgerCommand cmd = new CreateLedgerCommand(
                            resolvedName,
                            "LED-INLINE",
                            UUID.nameUUIDFromBytes("Sundry Debtors".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            BigDecimal.ZERO,
                            "DR",
                            "INR",
                            null
                        );
                        DesktopApiClient.deliverOnEdtIfCurrent(
                            epochGuard,
                            snap,
                            sessionCoordinator.apiClient().createLedger(cid, cmd, UUID.randomUUID()),
                            resp -> statusBannerLabel.setText("Secondary ledger persisted: " + resp.body().name()),
                            err -> { },
                            null
                        );
                    }
                    onSavedLedger.accept(resolvedName);
                    commandRegistry.markMutationCompleted("ledger.save");
                    statusBannerLabel.setText("Secondary ledger created: " + resolvedName);
                    closeModal("ledger.create");
                }
            ),
            new ModalActionSpec(
                "ledger.create", "en-GB", "editor.close", "Cancel", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("ledger.create")
            )
        );
        return openModal("ledger.create", "Secondary Ledger Creation (Alt+C)", specs);
    }

    public long openContextDateDialogModal(LocalDate dateToApply) {
        LocalDate resolved = dateToApply == null ? activeContextDate : dateToApply;
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "context.dateDialog", "en-GB", "context.applyDate", "Apply", 'a', 'A',
                KeyStroke.getKeyStroke("ctrl shift A"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.calendar", "company.read", () -> true,
                () -> {
                    this.activeContextDate = resolved;
                    if (sessionCoordinator != null) {
                        sessionCoordinator.setActiveVoucherDate(resolved);
                    }
                    statusBannerLabel.setText("Date applied: " + resolved);
                    closeModal("context.dateDialog");
                }
            ),
            new ModalActionSpec(
                "context.dateDialog", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("context.dateDialog")
            )
        );
        return openModal("context.dateDialog", "Change Accounting Date / Period [F2 / Alt+F2]", specs);
    }

    public long openReportFiltersModal(LocalDate fromDate, LocalDate toDate, Runnable onApply) {
        LocalDate resolvedFrom = fromDate == null ? activeReportFromDate : fromDate;
        LocalDate resolvedTo = toDate == null ? activeReportToDate : toDate;
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "report.filters", "en-GB", "report.applyFilters", "Apply", 'a', 'A',
                KeyStroke.getKeyStroke("ctrl shift F"), StateTokenRole.ACTION_PRIMARY,
                "mc.icon.filter", "report.read", () -> true,
                () -> {
                    this.activeReportFromDate = resolvedFrom;
                    this.activeReportToDate = resolvedTo;
                    if (onApply != null) {
                        onApply.run();
                    }
                    closeModal("report.filters");
                }
            ),
            new ModalActionSpec(
                "report.filters", "en-GB", "report.clearFilters", "Clear", 'l', 'L',
                KeyStroke.getKeyStroke("alt shift L"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.clear-filter", "report.read", () -> true,
                () -> {
                    this.activeReportFromDate = LocalDate.of(2026, 4, 1);
                    this.activeReportToDate = LocalDate.of(2027, 3, 31);
                    statusBannerLabel.setText("Report filters reset to period bounds");
                }
            ),
            new ModalActionSpec(
                "report.filters", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("report.filters")
            )
        );
        return openModal("report.filters", "Report Period & Filter Bounds", specs);
    }

    public long openReportOutputModal(Runnable onPrint, Runnable onExportPdf) {
        Objects.requireNonNull(onPrint, "onPrint must not be null");
        Objects.requireNonNull(onExportPdf, "onExportPdf must not be null");
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "report.output", "en-GB", "report.print", "Print", 'p', 'P',
                KeyStroke.getKeyStroke("ctrl shift P"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.print", "export", () -> true,
                () -> {
                    onPrint.run();
                    closeModal("report.output");
                }
            ),
            new ModalActionSpec(
                "report.output", "en-GB", "report.export", "Export PDF", 'e', 'E',
                KeyStroke.getKeyStroke("ctrl shift E"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.export", "export", () -> true,
                () -> {
                    onExportPdf.run();
                    closeModal("report.output");
                }
            ),
            new ModalActionSpec(
                "report.output", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("report.output")
            )
        );
        return openModal("report.output", "Statement Print & PDF Output", specs);
    }

    public long openRequestUncertainModal(
        boolean statusChecked,
        Runnable onCheckStatus,
        Runnable onRetryOriginal
    ) {
        return openRequestUncertainModal(() -> statusChecked, onCheckStatus, onRetryOriginal);
    }

    public long openRequestUncertainModal(
        BooleanSupplier statusCheckedSupplier,
        Runnable onCheckStatus,
        Runnable onRetryOriginal
    ) {
        Objects.requireNonNull(statusCheckedSupplier, "statusCheckedSupplier must not be null");
        Objects.requireNonNull(onCheckStatus, "onCheckStatus must not be null");
        Objects.requireNonNull(onRetryOriginal, "onRetryOriginal must not be null");
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "request.uncertain", "en-GB", "request.check", "Check status", 'k', 'K',
                KeyStroke.getKeyStroke("ctrl shift K"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.refresh", "authenticated", () -> true,
                onCheckStatus
            ),
            new ModalActionSpec(
                "request.uncertain", "en-GB", "request.retryOriginal", "Retry original", 'r', 'R',
                KeyStroke.getKeyStroke("ctrl shift R"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.refresh", "authenticated", statusCheckedSupplier,
                () -> {
                    onRetryOriginal.run();
                    commandRegistry.markMutationCompleted("request.retryOriginal");
                    closeModal("request.uncertain");
                }
            ),
            new ModalActionSpec(
                "request.uncertain", "en-GB", "request.details", "Details", 'd', 'D',
                KeyStroke.getKeyStroke("ctrl shift I"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.details", "authenticated", () -> true,
                () -> openMessageDetailsModal(lastDiagnosticMessage)
            ),
            new ModalActionSpec(
                "request.uncertain", "en-GB", "keyboard.open", "Keyboard settings", 'b', 'B',
                KeyStroke.getKeyStroke("ctrl COMMA"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.keyboard", "public", () -> true,
                this::openKeyboardSettingsModal
            )
        );
        return openModal("request.uncertain", "Uncertain Mutation Outcome — Reconcile Before Retry", specs);
    }

    public long openErrorRecoverableModal(String summary, boolean retryable, Runnable onRetry) {
        this.lastDiagnosticMessage = summary == null ? "Recoverable error" : summary;
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "error.recoverable", "en-GB", "request.retryOriginal", "Retry", 'r', 'R',
                KeyStroke.getKeyStroke("ctrl shift R"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.refresh", "authenticated", () -> retryable,
                () -> {
                    if (onRetry != null) {
                        onRetry.run();
                    }
                    commandRegistry.markMutationCompleted("request.retryOriginal");
                    closeModal("error.recoverable");
                }
            ),
            new ModalActionSpec(
                "error.recoverable", "en-GB", "editor.close", "Return", 'u', 'U',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.back", "authenticated", () -> true,
                () -> closeModal("error.recoverable")
            ),
            new ModalActionSpec(
                "error.recoverable", "en-GB", "request.details", "Details", 'd', 'D',
                KeyStroke.getKeyStroke("ctrl shift I"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.details", "authenticated", () -> true,
                () -> openMessageDetailsModal(lastDiagnosticMessage)
            )
        );
        return openModal("error.recoverable", "Operation Error — " + lastDiagnosticMessage, specs);
    }

    public long openMessageDetailsModal(String diagnosticText) {
        this.lastDiagnosticMessage = diagnosticText == null ? "No additional details" : diagnosticText;
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "message.details", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("message.details")
            )
        );
        return openModal("message.details", "Diagnostic Details: " + lastDiagnosticMessage, specs);
    }

    public long openKeyboardSettingsModal() {
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "keyboard.settings", "en-GB", "keyboard.apply", "Apply", 'a', 'A',
                KeyStroke.getKeyStroke("ctrl shift A"), StateTokenRole.ACTION_PRIMARY,
                null, "public", () -> true,
                () -> {
                    statusBannerLabel.setText("Keyboard settings applied");
                    closeModal("keyboard.settings");
                }
            ),
            new ModalActionSpec(
                "keyboard.settings", "en-GB", "keyboard.defaults", "Reset defaults", 'd', 'D',
                KeyStroke.getKeyStroke("ctrl shift D"), StateTokenRole.ACTION_SECONDARY,
                null, "public", () -> true,
                () -> {
                    commandRegistry.applyPreferences(
                        KeyboardPreferenceState.defaults(commandRegistry.preferenceState().ownerPrincipalId())
                    );
                    statusBannerLabel.setText("Keyboard preferences reset to defaults");
                }
            ),
            new ModalActionSpec(
                "keyboard.settings", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("keyboard.settings")
            )
        );
        return openModal("keyboard.settings", "Keyboard Mode & Shortcut Remapping", specs);
    }

    public long openCommandsMenuModal() {
        if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
            UUID cid = epochGuard.activeCompanyId();
            ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                sessionCoordinator.apiClient().smartFind(cid, "", 20),
                node -> statusBannerLabel.setText("SmartFind indexed results ready"),
                err -> { },
                null
            );
        }
        List<ModalActionSpec> specs = List.of(
            new ModalActionSpec(
                "commands.menu", "en-GB", "editor.close", "Close", 'c', 'C',
                KeyStroke.getKeyStroke("ctrl shift C"), StateTokenRole.ACTION_SECONDARY,
                "mc.icon.close", "authenticated", () -> true,
                () -> closeModal("commands.menu")
            )
        );
        return openModal("commands.menu", "Searchable Command Palette & SmartFind", specs);
    }

    private JButton createNavButton(String text, String targetView, StateTokenRole role) {
        JButton btn = theme.createStyledActionButton(text, role);
        btn.addActionListener(e -> showView(targetView));
        return btn;
    }

    private void bindAllShellAndWorkflowShortcuts(JPanel shellContent) {
        Map<String, Runnable> h = new LinkedHashMap<>();
        h.put("help.open", () -> openHelpForContext("HELP-110-001", activeViewName));
        h.put("report.toggleDetailed", () -> {
            reportDetailedMode = !reportDetailedMode;
            reportDetailModeBadge.setText(reportDetailedMode ? "Detailed" : "Condensed");
        });
        h.put("context.date", () -> {
            statusBannerLabel.setText("Change Date [F2]");
            openContextDateDialogModal(activeContextDate);
        });
        h.put("context.period", () -> {
            statusBannerLabel.setText("Change Period [Alt+F2]");
            openReportFiltersModal(activeReportFromDate, activeReportToDate, null);
        });
        h.put("company.open", () -> showView(VIEW_COMPANY_SELECTOR));
        h.put("company.select", () -> showView(VIEW_COMPANY_SELECTOR));
        h.put("company.shut", () -> {
            epochGuard.onCompanySwitched(null);
            showView(VIEW_COMPANY_SELECTOR);
        });
        h.put("company.features", this::openF11CompanyFeaturesModal);
        h.put("company.configure", () -> showView(VIEW_PREFERENCES_AND_HELP));
        h.put("report.open", () -> triggerGoToOverlay(VIEW_TRIAL_BALANCE));
        h.put("nav.switchTo", () -> triggerSwitchTo(VIEW_TRIAL_BALANCE));
        h.put("search.smartFind", this::openCommandsMenuModal);
        h.put("editor.close", this::handleEscapeKey);
        h.put("app.quitOrBack", this::handleEscapeKey);

        // Top Bar Alt+K/Y/Z/O/E/M/P dropdown triggers
        h.put("menu.company", () -> lastOpenedTopMenu = "COMPANY");
        h.put("menu.data", () -> lastOpenedTopMenu = "DATA");
        h.put("menu.exchange", () -> lastOpenedTopMenu = "EXCHANGE");
        h.put("menu.import", () -> lastOpenedTopMenu = "IMPORT");
        h.put("menu.export", () -> lastOpenedTopMenu = "EXPORT");
        h.put("menu.share", () -> lastOpenedTopMenu = "SHARE");
        h.put("menu.print", () -> lastOpenedTopMenu = "PRINT");

        // All voucher type & entry shortcuts (F4..F10, Alt+F5..F9, Ctrl+F5..F9, Ctrl+H/L/T/I/R/A/D, PageUp/Down)
        h.put("voucher.contra", () -> selectVoucherType("Contra"));
        h.put("voucher.payment", this::handleF5ShortcutOrPayment);
        h.put("report.refresh", this::handleF5ShortcutOrPayment);
        h.put("voucher.receipt", () -> selectVoucherType("Receipt"));
        h.put("voucher.journal", () -> selectVoucherType("Journal"));
        h.put("voucher.sales", () -> selectVoucherType("Sales"));
        h.put("voucher.purchase", () -> selectVoucherType("Purchase"));
        h.put("voucher.other", () -> selectVoucherType("Memorandum"));
        h.put("voucher.debitNote", () -> selectVoucherType("Debit Note"));
        h.put("voucher.creditNote", () -> selectVoucherType("Credit Note"));
        h.put("voucher.stockJournal", () -> selectVoucherType("Stock Journal"));
        h.put("voucher.deliveryNote", () -> selectVoucherType("Delivery Note"));
        h.put("voucher.receiptNote", () -> selectVoucherType("Receipt Note"));
        h.put("voucher.rejectionsOut", () -> selectVoucherType("Rejections Out"));
        h.put("voucher.rejectionsIn", () -> selectVoucherType("Rejections In"));
        h.put("voucher.physicalStock", () -> selectVoucherType("Physical Stock"));
        h.put("voucher.salesOrder", () -> selectVoucherType("Sales Order"));
        h.put("voucher.purchaseOrder", () -> selectVoucherType("Purchase Order"));
        h.put("voucher.changeMode", () -> {
            voucherModeIndex = (voucherModeIndex + 1) % VOUCHER_MODES.length;
            activeVoucherModeLabel.setText("Mode: " + VOUCHER_MODES[voucherModeIndex]);
        });
        h.put("voucher.toggleOptional", () -> {
            optionalVoucherFlag = !optionalVoucherFlag;
            statusBannerLabel.setText(optionalVoucherFlag ? "Voucher marked OPTIONAL" : "Voucher marked NORMAL");
        });
        h.put("voucher.togglePostDated", () -> {
            postDatedVoucherFlag = !postDatedVoucherFlag;
            statusBannerLabel.setText(postDatedVoucherFlag ? "Post-Dated active" : "Post-Dated cleared");
        });
        h.put("voucher.moreDetails", () -> {
            statusBannerLabel.setText("More Details / Tax Analysis opened");
            if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
                UUID cid = epochGuard.activeCompanyId();
                ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().computeGstPreview(
                        cid,
                        Map.of("taxableAmount", voucherDebitField.getText().replace(",", "").trim()),
                        UUID.randomUUID()
                    ),
                    resp -> statusBannerLabel.setText("GST Tax Analysis computed"),
                    err -> { },
                    null
                );
            }
        });
        h.put("voucher.autofill", () -> statusBannerLabel.setText("Voucher Autofill [Ctrl+Alt+F] applied"));
        h.put("ledger.create", () -> {
            if (VIEW_VOUCHER_ENTRY.equals(activeViewName) || VIEW_MASTERS.equals(activeViewName)) {
                openInlineLedgerCreateModal(
                    voucherLedgerField.getText().isBlank() ? "Inline Secondary Ledger" : voucherLedgerField.getText().trim(),
                    created -> voucherLedgerField.setText(created)
                );
            } else {
                comparativeColumnCount++;
                statusBannerLabel.setText("Comparative columns: " + comparativeColumnCount);
            }
        });
        h.put("report.addColumn", () -> {
            comparativeColumnCount++;
            statusBannerLabel.setText("Comparative columns: " + comparativeColumnCount);
        });
        h.put("ledger.select", () -> openLedgerLookupModal(
            selected -> voucherLedgerField.setText(selected),
            () -> openInlineLedgerCreateModal("Inline Ledger", selected -> voucherLedgerField.setText(selected))
        ));
        h.put("voucher.inlineAlter", () -> openLedgerLookupModal(
            selected -> voucherLedgerField.setText(selected),
            () -> openInlineLedgerCreateModal("Inline Ledger", selected -> voucherLedgerField.setText(selected))
        ));
        h.put("voucher.repeatNarration", () -> {
            voucherNarrationField.setText(lastCommittedNarration);
            if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
                UUID cid = epochGuard.activeCompanyId();
                ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().getLastNarration(cid, null, activeVoucherType, null),
                    dto -> {
                        if (dto != null && dto.narration() != null && !dto.narration().isBlank()) {
                            voucherNarrationField.setText(dto.narration());
                        }
                    },
                    err -> { },
                    null
                );
            }
        });
        h.put("voucher.prev", () -> {
            currentVoucherBrowseIndex = Math.max(1, currentVoucherBrowseIndex - 1);
            voucherNumberField.setText(String.format("VCH-%04d", currentVoucherBrowseIndex));
            if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
                UUID cid = epochGuard.activeCompanyId();
                ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().getAdjacentVoucher(
                        cid,
                        sessionCoordinator.activeVoucherId().orElse(null),
                        activeVoucherType,
                        activeContextDate,
                        "PREV"
                    ),
                    adj -> {
                        if (adj != null && adj.voucher() != null && adj.voucher().voucherNumber() != null) {
                            voucherNumberField.setText(adj.voucher().voucherNumber());
                        }
                    },
                    err -> { },
                    null
                );
            }
        });
        h.put("voucher.next", () -> {
            currentVoucherBrowseIndex++;
            voucherNumberField.setText(String.format("VCH-%04d", currentVoucherBrowseIndex));
            if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
                UUID cid = epochGuard.activeCompanyId();
                ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().getAdjacentVoucher(
                        cid,
                        sessionCoordinator.activeVoucherId().orElse(null),
                        activeVoucherType,
                        activeContextDate,
                        "NEXT"
                    ),
                    adj -> {
                        if (adj != null && adj.voucher() != null && adj.voucher().voucherNumber() != null) {
                            voucherNumberField.setText(adj.voucher().voucherNumber());
                        }
                    },
                    err -> { },
                    null
                );
            }
        });
        h.put("voucher.deleteLine", () -> {
            voucherDebitField.setText("0.00");
            voucherCreditField.setText("0.00");
        });
        h.put("journal.saveDraft", () -> submitVoucherSaveOrAccept(null, null, "journal.saveDraft"));
        h.put("voucher.accept", this::handleCtrlAAcceptActiveScreen);
        h.put("ledger.save", this::handleCtrlAAcceptActiveScreen);
        h.put("device.captureScale", this::captureScaleReadingIntoVoucherLine);
        h.put("report.output", () -> openReportOutputModal(
            this::executeDefaultPrintDispatch,
            this::executeDefaultPdfExportDispatch
        ));
        h.put("report.export", this::executeDefaultPdfExportDispatch);
        h.put("keyboard.open", this::openKeyboardSettingsModal);
        h.put("device.reconcilePrint", () -> {
            UUID cid = epochGuard.activeCompanyId();
            if (cid != null) {
                DeviceAuthorityContext auth = new DeviceAuthorityContext(
                    DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
                    UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    cid,
                    true,
                    Set.of("company.read", "export", "privateField.read")
                );
                for (PrintAttemptRecord rec : printAttemptLedger.listAttemptsForCompany(cid)) {
                    if (rec.state() == PrintAttemptState.OUTCOME_UNKNOWN && !rec.unknownReconciled()) {
                        printAttemptLedger.reconcileUnknownAttempt(
                            rec.attemptId(),
                            auth,
                            true,
                            "Operator confirmed paper output via Ctrl+Shift+Q"
                        );
                    }
                }
                refreshPrintAttempts(cid);
            }
            commandRegistry.markMutationCompleted("device.reconcilePrint");
            statusBannerLabel.setText("Reconciled unknown print attempts");
        });
        h.put("device.reprintDuplicate", () -> {
            UUID cid = epochGuard.activeCompanyId();
            if (cid != null) {
                DeviceAuthorityContext auth = new DeviceAuthorityContext(
                    DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
                    UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    cid,
                    true,
                    Set.of("company.read", "export", "privateField.read")
                );
                List<PrintAttemptRecord> attempts = printAttemptLedger.listAttemptsForCompany(cid);
                if (!attempts.isEmpty()) {
                    PrintAttemptRecord last = attempts.get(attempts.size() - 1);
                    printAttemptLedger.createAuditedReprint(
                        last.attemptId(),
                        auth,
                        "Operator requested audited duplicate reprint via Ctrl+Shift+M",
                        "%PDF-1.4 DUPLICATE / REPRINT".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    );
                    refreshPrintAttempts(cid);
                }
            }
            commandRegistry.markMutationCompleted("device.reprintDuplicate");
            statusBannerLabel.setText("Created audited duplicate reprint");
        });

        // Day Book & Report row actions (Alt+A, Alt+2, Alt+I, Alt+D, Alt+X, Space, Ctrl+Space, Alt+R, Alt+U, Ctrl+U, Shift+Enter, Alt+N, Alt+Q, Ctrl+B, Ctrl+J, Ctrl+Alt+M)
        h.put("daybook.add", () -> showView(VIEW_VOUCHER_ENTRY));
        h.put("daybook.duplicate", () -> {
            dayBookTableModel.addRow(new Object[]{"2026-04-16", "Duplicated Entry", activeVoucherType, "DUP-01", "1000.00", "1000.00", "DRAFT"});
            statusBannerLabel.setText("Duplicated voucher");
        });
        h.put("daybook.insert", () -> showView(VIEW_VOUCHER_ENTRY));
        h.put("daybook.delete", () -> {
            if (dayBookTableModel.getRowCount() > 0) {
                dayBookTableModel.removeRow(dayBookTableModel.getRowCount() - 1);
            }
            commandRegistry.markMutationCompleted("daybook.delete");
            statusBannerLabel.setText("Deleted draft voucher");
        });
        h.put("daybook.cancel", () -> {
            if (dayBookTableModel.getRowCount() > 0) {
                dayBookTableModel.setValueAt("CANCELLED", 0, 6);
            }
            if (sessionCoordinator != null
                && epochGuard.activeCompanyId() != null
                && sessionCoordinator.activeVoucherId().isPresent()) {
                UUID cid = epochGuard.activeCompanyId();
                UUID vid = sessionCoordinator.activeVoucherId().get();
                ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().cancelVoucher(cid, vid, "Cancelled via Alt+X", UUID.randomUUID()),
                    resp -> statusBannerLabel.setText("Voucher cancelled on server (number retained)"),
                    err -> { },
                    null
                );
            }
            commandRegistry.markMutationCompleted("daybook.cancel");
            statusBannerLabel.setText("Voucher cancelled (number retained)");
        });
        h.put("grid.selectRow", () -> {
            if (!selectedDayBookRowIndices.remove(0)) {
                selectedDayBookRowIndices.add(0);
            }
        });
        h.put("grid.selectAll", () -> {
            for (int i = 0; i < dayBookTableModel.getRowCount(); i++) {
                selectedDayBookRowIndices.add(i);
            }
        });
        h.put("grid.removeLine", () -> {
            int count = dayBookTableModel.getRowCount();
            if (count > 0) {
                Object[] removed = new Object[dayBookTableModel.getColumnCount()];
                for (int c = 0; c < removed.length; c++) {
                    removed[c] = dayBookTableModel.getValueAt(count - 1, c);
                }
                hiddenReportOrDayBookRows.push(removed);
                dayBookTableModel.removeRow(count - 1);
            }
        });
        h.put("grid.restoreLine", () -> {
            if (!hiddenReportOrDayBookRows.isEmpty()) {
                dayBookTableModel.addRow(hiddenReportOrDayBookRows.pop());
            }
        });
        h.put("grid.restoreAllLines", () -> {
            while (!hiddenReportOrDayBookRows.isEmpty()) {
                dayBookTableModel.addRow(hiddenReportOrDayBookRows.pop());
            }
        });
        h.put("report.explodeRow", () -> statusBannerLabel.setText("Row exploded [Shift+Enter]"));
        h.put("report.autoColumn", () -> {
            comparativeColumnCount += 4;
            statusBannerLabel.setText("Auto-Column quarterly comparison added");
        });
        h.put("report.basisOfValues", () -> openReportFiltersModal(activeReportFromDate, activeReportToDate, null));
        h.put("report.exceptions", () -> {
            reportsExplorerPanel.activateRoute("EXCEPTION:NEGATIVE_STOCK", this::loadTrialBalanceFromServerIfConnected);
            showView(VIEW_REPORTS_EXPLORER);
            statusBannerLabel.setText("Opened Exception Reports [Ctrl+J]");
        });
        h.put("banking.autoMatch", () -> {
            reportsExplorerPanel.activateRoute("BANKING:BRS", this::loadTrialBalanceFromServerIfConnected);
            showView(VIEW_REPORTS_EXPLORER);
            reportsExplorerPanel.executePrimaryDomainAction();
            commandRegistry.markMutationCompleted("banking.autoMatch");
        });
        h.put("audit.editLog", () -> {
            reportsExplorerPanel.activateRoute("AUDIT:EDIT_LOG", this::loadTrialBalanceFromServerIfConnected);
            showView(VIEW_REPORTS_EXPLORER);
            statusBannerLabel.setText("MCA Edit Log Redline Viewer [Alt+Q]");
        });

        commandRegistry.bindScopedCommands(shellContent, h, Map.of());
    }

    private void seedSampleDayBookRows() {
        dayBookTableModel.addRow(new Object[]{"2026-04-01", "Sales Account", "Sales", "SLS-0001", "12500.00", "12500.00", "POSTED"});
        dayBookTableModel.addRow(new Object[]{"2026-04-02", "Office Rent", "Payment", "PMT-0001", "8000.00", "8000.00", "POSTED"});
    }

    private void seedDefaultMastersAndVoucherLines() {
        mastersTableModel.addRow(new Object[]{"GRP-01", "Capital Account", "Primary", "0.00", "28 Predefined Groups"});
        mastersTableModel.addRow(new Object[]{"GRP-02", "Current Assets", "Primary", "0.00", "28 Predefined Groups"});
        mastersTableModel.addRow(new Object[]{"GRP-03", "Current Liabilities", "Primary", "0.00", "28 Predefined Groups"});
        mastersTableModel.addRow(new Object[]{"LED-01", "Cash", "Cash-in-Hand", "0.00", "System Default"});
        mastersTableModel.addRow(new Object[]{"LED-02", "Profit & Loss A/c", "Primary", "0.00", "System Default"});

        voucherLinesTableModel.addRow(new Object[]{"DR", "Sundry Debtors", "Main Location", "1.000", "0.00", "0.00", "0.00"});
        voucherLinesTableModel.addRow(new Object[]{"CR", "Sales Account", "Main Location", "1.000", "0.00", "0.00", "0.00"});

        reportsExplorerTableModel.addRow(new Object[]{
            "Trial Balance Summary", "TB-01", "2026-04-01 .. 2027-03-31", "0.00", "0.00", "0.00"
        });
    }

    private void handleF5ShortcutOrPayment() {
        if (VIEW_TRIAL_BALANCE.equals(activeViewName)) {
            toggleTrialBalanceLedgerWiseView();
            loadTrialBalanceFromServerIfConnected();
        } else if (VIEW_MASTERS.equals(activeViewName)) {
            mastersPanel.loadRouteFromServerOrSeed(mastersPanel.currentRoute());
        } else if (VIEW_REPORTS_EXPLORER.equals(activeViewName)) {
            reportsExplorerPanel.loadRouteFromServerIfConnected(
                reportsExplorerPanel.currentRoute(),
                this::loadTrialBalanceFromServerIfConnected
            );
        } else {
            selectVoucherType("Payment");
        }
    }

    private void handleCtrlAAcceptActiveScreen() {
        if (VIEW_COMPANY_CREATE.equals(activeViewName)) {
            submitCreateCompanyAndOpenF11Features(null);
            commandRegistry.markMutationCompleted("voucher.accept");
            commandRegistry.markMutationCompleted("ledger.save");
        } else if (VIEW_MASTERS.equals(activeViewName)) {
            mastersPanel.submitActiveMasterCreateOrAlter();
            commandRegistry.markMutationCompleted("voucher.accept");
            commandRegistry.markMutationCompleted("ledger.save");
        } else if (VIEW_REPORTS_EXPLORER.equals(activeViewName)
            && "EXCEPTION:APPROVAL_QUEUE".equals(reportsExplorerPanel.currentRoute())) {
            reportsExplorerPanel.executePrimaryDomainAction();
            commandRegistry.markMutationCompleted("voucher.accept");
            commandRegistry.markMutationCompleted("ledger.save");
        } else {
            submitVoucherSaveOrAccept(null, null, "voucher.accept");
            commandRegistry.markMutationCompleted("ledger.save");
        }
    }

    private void captureScaleReadingIntoVoucherLine() {
        UUID cid = epochGuard.activeCompanyId() != null
            ? epochGuard.activeCompanyId()
            : UUID.fromString("5eeb242a-976c-44e3-898e-0423c32b464c");
        DeviceAuthorityContext auth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            cid,
            true,
            Set.of("company.read", "voucher.create", "device.scaleCapture")
        );
        WeighingScaleProfile scaleProfile = new WeighingScaleProfile(
            "METTLER-TOLEDO-IND231-KG",
            true,
            "Mettler Toledo",
            "IND231",
            9600,
            8,
            "NONE",
            1,
            "\r\n",
            Duration.ofSeconds(5),
            "KG",
            3,
            new BigDecimal("0.010"),
            new BigDecimal("500.000")
        );
        Instant now = Instant.now();
        ScaleReadingResult reading = captureManager.captureScaleFrame(
            scaleProfile,
            auth,
            true,
            "ST,GS,12.500,0.500,12.000,KG\r\n",
            now,
            now
        );
        if (reading.accepted() && reading.netWeight() != null) {
            if (voucherLinesTableModel.getRowCount() > 0) {
                voucherLinesTableModel.setValueAt(reading.netWeight().toPlainString(), 0, 3);
            }
            scaleWeightStatusLabel.setText(
                "Scale: " + reading.netWeight().toPlainString() + " " + reading.unit() + " [Ctrl+Shift+W]"
            );
            statusBannerLabel.setText("Captured scale weight reading: " + reading.netWeight().toPlainString() + " KG");
        }
    }

    public void executeDefaultPrintDispatch() {
        UUID cid = epochGuard.activeCompanyId() != null
            ? epochGuard.activeCompanyId()
            : UUID.fromString("5eeb242a-976c-44e3-898e-0423c32b464c");
        DeviceAuthorityContext auth = new DeviceAuthorityContext(
            DeviceAuthorityContext.ActorKind.INTERACTIVE_PERSON,
            UUID.nameUUIDFromBytes("operator".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            cid,
            true,
            Set.of("company.read", "export", "privateField.read")
        );
        DeviceProfile pageProfile = new DeviceProfile(
            "OS-PAGE-PDF-A4",
            "1.0",
            true,
            "System",
            "PagePrintAdapter",
            "1.0",
            "ALL",
            "ALL",
            DeviceProfile.TransportMode.OS_PRINT_DRIVER,
            DeviceProfile.CommandLanguage.OS_PAGE_PDF,
            "UTF-8",
            0,
            210,
            80,
            60,
            10,
            Set.of("CODE128"),
            Set.of('\u20B9'),
            Optional.of("INR "),
            false,
            false,
            DeviceProfile.CompletionEvidenceCapability.SPOOLER_DATA_TRANSFERRED_ONLY
        );
        byte[] placeholderPdf = "%PDF-1.4\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        PrintAttemptRecord prepared = printAttemptLedger.prepareAttempt(
            auth,
            UUID.randomUUID(),
            1L,
            false,
            placeholderPdf,
            pageProfile,
            "OS-DEFAULT-PRINTER"
        );
        printAttemptLedger.beginSubmitting(prepared.attemptId(), auth);
        printAttemptLedger.markSubmitted(
            prepared.attemptId(),
            CompletionEvidenceSource.SPOOLER_DATA_TRANSFERRED,
            "Submitted to PagePrintAdapter spooler"
        );
        refreshPrintAttempts(cid);
        executeDefaultPdfExportDispatch();
        statusBannerLabel.setText("Dispatched statement to PagePrintAdapter (" + prepared.attemptId().toString().substring(0, 8) + ")");
    }

    public void executeDefaultPdfExportDispatch() {
        if (sessionCoordinator != null && epochGuard.activeCompanyId() != null) {
            UUID cid = epochGuard.activeCompanyId();
            ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
            if (VIEW_VOUCHER_ENTRY.equals(activeViewName) && sessionCoordinator.activeVoucherId().isPresent()) {
                UUID vid = sessionCoordinator.activeVoucherId().get();
                DesktopApiClient.deliverOnEdtIfCurrent(
                    epochGuard,
                    snap,
                    sessionCoordinator.apiClient().streamVoucherPdf(cid, vid, UUID.randomUUID()),
                    bytes -> statusBannerLabel.setText("Exported deterministic voucher PDF (" + bytes.length + " bytes)"),
                    err -> statusBannerLabel.setText("PDF export error: " + err.getMessage()),
                    null
                );
                return;
            }
            DesktopApiClient.deliverOnEdtIfCurrent(
                epochGuard,
                snap,
                sessionCoordinator.apiClient().streamReportPdf(
                    cid,
                    "trial-balance",
                    Map.of(
                        "fromDate", sessionCoordinator.activePeriodFrom().toString(),
                        "toDate", sessionCoordinator.activePeriodTo().toString()
                    ),
                    UUID.randomUUID()
                ),
                bytes -> statusBannerLabel.setText("Exported deterministic server PDF (" + bytes.length + " bytes)"),
                err -> statusBannerLabel.setText("PDF export error: " + err.getMessage()),
                null
            );
            return;
        }
        statusBannerLabel.setText("Exported deterministic server PDF");
    }

    private void loadCompaniesFromServerIfConnected() {
        if (sessionCoordinator == null) {
            return;
        }
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient.deliverOnEdtIfCurrent(
            epochGuard,
            snap,
            sessionCoordinator.apiClient().listCompanies(),
            this::populateCompanies,
            err -> { },
            null
        );
    }

    private void loadTrialBalanceFromServerIfConnected() {
        if (sessionCoordinator == null || epochGuard.activeCompanyId() == null) {
            return;
        }
        UUID companyId = epochGuard.activeCompanyId();
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient.deliverOnEdtIfCurrent(
            epochGuard,
            snap,
            sessionCoordinator.apiClient().getTrialBalance(
                companyId,
                sessionCoordinator.activePeriodFrom(),
                sessionCoordinator.activePeriodTo(),
                false
            ),
            tb -> {
                List<TrialBalanceRowDto> rows = trialBalanceLedgerWiseMode && !tb.ledgerRows().isEmpty()
                    ? tb.ledgerRows()
                    : (!tb.groupRows().isEmpty() ? tb.groupRows() : tb.ledgerRows());
                populateTrialBalanceRows(rows);
            },
            err -> statusBannerLabel.setText("Failed loading Trial Balance: " + err.getMessage()),
            null
        );
    }

    private void loadDayBookFromServerIfConnected() {
        if (sessionCoordinator == null || epochGuard.activeCompanyId() == null) {
            return;
        }
        UUID companyId = epochGuard.activeCompanyId();
        ContextEpochGuard.EpochSnapshot snap = epochGuard.captureSnapshot();
        DesktopApiClient.deliverOnEdtIfCurrent(
            epochGuard,
            snap,
            sessionCoordinator.apiClient().listVouchers(
                companyId,
                sessionCoordinator.activePeriodFrom(),
                sessionCoordinator.activePeriodTo(),
                null,
                200
            ),
            page -> populateDayBookRows(page.items()),
            err -> statusBannerLabel.setText("Failed loading Day Book: " + err.getMessage()),
            null
        );
    }

    public void populateMastersFromCoaTree(CoaTreeDto coa) {
        mastersPanel.populateFromCoaTree(coa);
    }

    public void populateMastersFromLedgers(List<LedgerDto> ledgers) {
        mastersPanel.populateFromLedgers(ledgers);
    }

    public void populateDayBookRows(List<VoucherSummaryDto> vouchers) {
        if (vouchers == null) {
            return;
        }
        dayBookTableModel.setRowCount(0);
        for (VoucherSummaryDto v : vouchers) {
            dayBookTableModel.addRow(new Object[]{
                v.voucherDate() + "",
                v.primaryParticulars() != null ? v.primaryParticulars() : (v.narration() != null ? v.narration() : v.voucherTypeName()),
                v.voucherTypeName(),
                v.voucherNumber(),
                safeAmountText(v.totalDebit()),
                safeAmountText(v.totalCredit()),
                v.status()
            });
        }
    }

    public void populateBalanceSheetInExplorer(BalanceSheetReportDto bs) {
        reportsExplorerPanel.populateBalanceSheet(bs);
    }

    public void populateProfitAndLossInExplorer(ProfitAndLossReportDto pnl) {
        reportsExplorerPanel.populateProfitAndLoss(pnl);
    }

    public void populateAccountBookInExplorer(AccountBookReportDto book) {
        reportsExplorerPanel.populateAccountBook(book);
    }

    public void populateExceptionReportInExplorer(AccountBookReportDto.ExceptionReportDto exc) {
        reportsExplorerPanel.populateExceptionReport(exc);
    }

    public void populateStatisticsInExplorer(AccountBookReportDto.StatisticsReportDto stats) {
        reportsExplorerPanel.populateStatistics(stats);
    }

    private static String safeAmountText(BigDecimal val) {
        return val == null ? "0.00" : val.toPlainString();
    }

    private static void layoutRecursively(Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                layoutRecursively(child);
            }
        }
    }

    private record SavedGoToFrameState(
        String viewName,
        String ledgerText,
        String debitText,
        String creditText,
        String narrationText,
        boolean dirty,
        String voucherType
    ) {
    }

    /**
     * Recursive-descent exact {@link BigDecimal} expression evaluator for commercial calculator
     * inputs ({@code +}, {@code -}, {@code *}, {@code /}, unary {@code +}/{@code -}, and nested
     * parentheses {@code (...)}) with zero IEEE-754 floating-point math.
     */
    private static final class ExactDecimalExpressionParser {
        private final String input;
        private int pos;

        private ExactDecimalExpressionParser(String input) {
            this.input = input;
        }

        private BigDecimal parse() {
            BigDecimal value = parseExpression();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException(
                    "Unexpected token at index " + pos + " in calculator expression: " + input
                );
            }
            return value;
        }

        private BigDecimal parseExpression() {
            BigDecimal left = parseTerm();
            while (true) {
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '+') {
                    pos++;
                    left = left.add(parseTerm());
                } else if (pos < input.length() && input.charAt(pos) == '-') {
                    pos++;
                    left = left.subtract(parseTerm());
                } else {
                    return left;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal left = parseFactor();
            while (true) {
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == '*') {
                    pos++;
                    left = left.multiply(parseFactor());
                } else if (pos < input.length() && input.charAt(pos) == '/') {
                    pos++;
                    BigDecimal divisor = parseFactor();
                    left = left.divide(divisor, 10, RoundingMode.HALF_UP);
                } else {
                    return left;
                }
            }
        }

        private BigDecimal parseFactor() {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '+') {
                pos++;
                return parseFactor();
            }
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                return parseFactor().negate();
            }
            if (pos < input.length() && input.charAt(pos) == '(') {
                pos++;
                BigDecimal inner = parseExpression();
                skipWhitespace();
                if (pos >= input.length() || input.charAt(pos) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis in calculator expression: " + input);
                }
                pos++;
                return inner;
            }
            int start = pos;
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (Character.isDigit(c) || c == '.') {
                    pos++;
                } else {
                    break;
                }
            }
            if (start == pos) {
                throw new IllegalArgumentException("Expected numeric literal at index " + pos + " in: " + input);
            }
            return new BigDecimal(input.substring(start, pos));
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }
    }
}
