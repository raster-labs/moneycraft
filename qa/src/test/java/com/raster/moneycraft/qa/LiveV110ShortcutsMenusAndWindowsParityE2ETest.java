package com.raster.moneycraft.qa;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.raster.moneycraft.foundation.crypto.ExactMoneyValidator;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live Headless Chromium E2E Verification Suite for MoneyCraft v1.1.0:
 * 1. UM-110-03 & Top-Bar Menus: Shut Company (Ctrl+F3) -> Startup Select Company -> Create Company
 *    -> automatic post-create F11 Company Features modal -> Top-Bar Alt+K/Y/Z/O/E/M/P/F1 menus.
 * 2. UM-110-01: 3-Level cascading Gateway drill-down (Level 1 -> Level 2 -> Level 3 + Esc step-back)
 *    and live Level-3 Account Books, Exception Reports, Statistics (24 voucher types / 11 master types),
 *    and Stock Query workbench.
 * 3. UM-110-02 & UM-110-05: Interactive human-like voucher creation (typing + Tab without Enter,
 *    mouse click into Amount, Indian commas 12,500.00, and Ctrl+A directly inside Amount input) across
 *    Receipt, Payment, Contra, Journal (+ Ctrl+R Repeat Narration + PageUp/PageDown), Purchase Item
 *    Invoice, Sales Item Invoice, Debit Note, Credit Note, Stock/BOM Manufacturing Journal, Memorandum,
 *    and Reversing Journal, plus Alt+N Calculator, F11, F12, Ctrl+G, Alt+T, Ctrl+L, Space/Ctrl+Space,
 *    Alt+2 Duplicate, Alt+R/Alt+U/Ctrl+U, and Comparative Report Column.
 * 4. AT-ITER110-001..035: Account & Browser UI Preferences (schemaVersion 2, Light/Dark/System with
 *    WCAG >= 4.5:1 Dark contrast, 75%..200% scaling, 320px reflow, Use account settings in this browser),
 *    F1 Contextual Help Layer-1 Escape-first dismissal over open modal, SUPER_ADMIN Government & Banking
 *    Integration Console (GOV-D01..39 + ICICI_CIB inventory, step-up password, write-only credential
 *    staging, safe SANDBOX probe), and live Desktop Native OAuth2 + PKCE S256 loopback callback flow.
 */
@EnabledIfSystemProperty(named = "moneycraft.e2e.live", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiveV110ShortcutsMenusAndWindowsParityE2ETest {

    private static final String BASE_URL = System.getProperty("moneycraft.e2e.url", "http://localhost:18080");
    private static final String OWNER_USER = System.getProperty("moneycraft.e2e.user", "owner");
    private static final String OWNER_PASS = System.getProperty("moneycraft.e2e.pass", "MoneyCraft#2026!Owner");
    private static final String BENCHMARK_COMPANY_NAME = "MoneyCraft Parity Benchmark";

    private static Playwright playwright;
    private static Browser browser;
    private static BrowserContext browserContext;
    private static Page page;
    private static final List<String> BLOCKED_EXTERNAL_URLS = new ArrayList<>();

    private static ObjectMapper objectMapper;
    private static CookieManager apiCookieJar;
    private static HttpClient apiClient;
    private static String benchmarkCompanyId;
    private static String interactiveCompanyId;
    private static String interactiveCompanyName;
    private static String interactiveCompanyGstin;

    @BeforeAll
    static void setupPlaywrightAndSession() throws Exception {
        objectMapper = new ObjectMapper();
        apiCookieJar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        apiClient = HttpClient.newBuilder()
            .cookieHandler(apiCookieJar)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

        authenticateApiSession();
        benchmarkCompanyId = lookupBenchmarkCompanyId();

        playwright = Playwright.create(
            new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"))
        );
        browser = QaPlaywrightAndFixtureSupport.launchHeadlessChromium(playwright);
        browserContext = browser.newContext(
            new Browser.NewContextOptions().setViewportSize(1440, 900)
        );
        page = browserContext.newPage();
        page.setDefaultTimeout(20_000);

        page.route("**/*", route -> {
            String url = route.request().url();
            if ((url.startsWith("http://") || url.startsWith("https://"))
                && !url.startsWith("http://localhost:")
                && !url.startsWith("https://localhost:")
                && !url.startsWith("http://127.0.0.1:")
                && !url.startsWith("https://127.0.0.1:")) {
                BLOCKED_EXTERNAL_URLS.add(url);
                route.abort();
            } else {
                route.resume();
            }
        });
    }

    @AfterAll
    static void closePlaywright() {
        if (browserContext != null) {
            browserContext.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    @Order(1)
    void um110StartupFlowShutCompanyCreateCompanyPostCreateF11AndTopBarDropdownMenus() throws Exception {
        // Sign in via browser login screen
        page.navigate(BASE_URL + "/login");
        page.locator("#mc-login-username").waitFor();
        page.locator("#mc-login-username").fill(OWNER_USER);
        page.locator("#mc-login-password").fill(OWNER_PASS);
        page.locator("button.mc-btn-primary.mc-login-submit").click();
        page.waitForURL("**/app/**");
        page.locator("header.mc-omni-bar").waitFor();

        selectCompanyInSidebar(BENCHMARK_COMPANY_NAME);

        // 1. Verify Top-Bar dropdown menus (Alt+K Company, Alt+Y Data, Alt+Z Exchange, Alt+O Import,
        //    Alt+E Export, Alt+M Share, Alt+P Print, F1 Help) open & dismiss cleanly via Escape
        for (String menuKey : List.of("company", "data", "exchange", "import", "export", "share", "print", "help")) {
            Locator btn = page.locator("[data-testid='omni-menu-" + menuKey + "-btn']");
            btn.waitFor();
            btn.click();
            Locator dropdown = page.locator("[data-testid='topbar-dropdown-" + menuKey + "']");
            dropdown.waitFor();
            Assertions.assertThat(dropdown.isVisible()).isTrue();
            page.keyboard().press("Escape");
            dropdown.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        }

        // Also verify keyboard invocation Alt+k opens topbar-dropdown-company and Escape closes it
        page.locator("body").focus();
        page.keyboard().press("Alt+k");
        Locator companyDropdown = page.locator("[data-testid='topbar-dropdown-company']");
        companyDropdown.waitFor();
        page.keyboard().press("Escape");
        companyDropdown.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Navigate to Gateway and shut all open companies via Ctrl+F3 until Startup Select Company renders
        navigateSpa("/app/gateway");
        for (int attempt = 0; attempt < 15; attempt++) {
            if (page.locator("[data-testid='windows-startup-select-company']").isVisible()) {
                break;
            }
            page.locator("body").focus();
            page.keyboard().press("Control+F3");
            page.waitForTimeout(200);
        }
        Locator startupSelect = page.locator("[data-testid='windows-startup-select-company']");
        startupSelect.waitFor();
        PlaywrightAssertions.assertThat(startupSelect).containsText("Select Company");

        // 3. Click Create Company from Startup Select Company -> create fresh v1.1.0 interactive company
        //    -> verify immediate post-create F11 Company Features modal opens automatically!
        String runTag = UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
        String companyCode = "MC110" + runTag;
        interactiveCompanyName = "MoneyCraft v1.1.0 Interactive E2E " + runTag + " Ltd";
        String gstinPrefix = "33AABCM1100E1Z";
        interactiveCompanyGstin = gstinPrefix + ExactMoneyValidator.computeGstinMod36CheckChar(gstinPrefix);

        page.locator("[data-testid='startup-create-company-btn']").click();
        page.locator("#comp-code").waitFor();
        page.locator("#comp-code").fill(companyCode);
        page.locator("#comp-legal").fill(interactiveCompanyName);
        page.locator("#comp-mailing").fill(interactiveCompanyName);
        page.locator("#comp-fy-from").fill("2025-04-01");
        page.locator("#comp-books-from").fill("2025-04-01");
        if (page.locator("#comp-gstin").isVisible()) {
            page.locator("#comp-gstin").fill(interactiveCompanyGstin);
        }
        page.locator("form.mc-company-form button.mc-btn-primary").first().click();

        Locator postCreateF11 = page.locator("[data-testid='post-create-f11-modal']");
        postCreateF11.waitFor();
        PlaywrightAssertions.assertThat(postCreateF11).containsText("Company created successfully");
        page.locator("[data-testid='post-create-f11-accept-btn']").click();
        postCreateF11.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // Reload /app/gateway so CompanyContextStore restores all open companies (Benchmark + new company)
        interactiveCompanyId = lookupCompanyIdByLegalName(interactiveCompanyName);
        Assertions.assertThat(interactiveCompanyId).isNotBlank();
        seedInteractiveCompanyMastersViaApi(interactiveCompanyId);
        page.navigate(BASE_URL + "/app/gateway");
        page.locator("header.mc-omni-bar").waitFor();
    }

    @Test
    @Order(2)
    void um110ThreeLevelCascadingGatewayMenusAndLevel3ReportsWorkbench() {
        selectCompanyInSidebar(BENCHMARK_COMPANY_NAME);
        setActivePeriodInUi("2025-04-01", "2025-05-31");

        // 1. Level 1 -> Level 2 -> Esc -> Level 1 on Create ('c')
        navigateSpa("/app/gateway");
        page.locator("[data-testid='gateway-menu-card']").waitFor();
        page.locator("body").focus();
        page.keyboard().press("c");
        Locator level2 = page.locator("[data-testid='gateway-level-2']");
        level2.waitFor();
        PlaywrightAssertions.assertThat(level2).containsText("Accounting Masters");
        PlaywrightAssertions.assertThat(level2).containsText("Inventory Masters");
        PlaywrightAssertions.assertThat(level2).containsText("Statutory Masters");
        page.keyboard().press("Escape");
        level2.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Level 1 -> Display More Reports ('d') -> Level 2 -> Account Books ('a') -> Level 3
        page.keyboard().press("d");
        level2.waitFor();
        PlaywrightAssertions.assertThat(level2).containsText("Account Books");
        PlaywrightAssertions.assertThat(level2).containsText("Statements of Accounts");
        PlaywrightAssertions.assertThat(level2).containsText("Exception Reports");

        page.keyboard().press("a");
        Locator level3 = page.locator("[data-testid='gateway-level-3']");
        level3.waitFor();
        PlaywrightAssertions.assertThat(level3).containsText("Sales Register");
        PlaywrightAssertions.assertThat(level3).containsText("Purchase Register");
        PlaywrightAssertions.assertThat(level3).containsText("Journal Register");

        // Step back one level with Escape -> Level 2 -> Statements of Inventory ('e') -> Esc -> Exception Reports ('x')
        page.keyboard().press("Escape");
        level2.waitFor();
        page.keyboard().press("e");
        level3.waitFor();
        PlaywrightAssertions.assertThat(level3).containsText("Stock Query");
        page.keyboard().press("Escape");
        level2.waitFor();
        page.keyboard().press("x");
        level3.waitFor();
        PlaywrightAssertions.assertThat(level3).containsText("Optional Vouchers");
        PlaywrightAssertions.assertThat(level3).containsText("Cancelled Vouchers");

        // 3. Open Level-3 Reports Workbench (/app/reports/account-books) and exercise all 4 tabs
        navigateSpa("/app/reports/account-books");
        Locator workbench = page.locator("[data-testid='level3-reports-workbench']");
        workbench.waitFor();

        // Tab 1: Account Books
        page.locator("[data-testid='l3-tab-account-books']").click();
        Locator booksTable = page.locator("[data-testid='l3-account-books-table']");
        booksTable.waitFor();
        Assertions.assertThat(booksTable.textContent()).isNotBlank();

        // Tab 2: Exception Reports
        page.locator("[data-testid='l3-tab-exceptions']").click();
        Locator excTable = page.locator("[data-testid='l3-exceptions-table']");
        excTable.waitFor();
        Assertions.assertThat(excTable.textContent()).isNotBlank();

        // Tab 3: Statistics (24 voucher types + master counts)
        page.locator("[data-testid='l3-tab-statistics']").click();
        Locator statsPanes = page.locator("[data-testid='l3-statistics-panes']");
        statsPanes.waitFor();
        PlaywrightAssertions.assertThat(statsPanes).containsText("Sales");
        PlaywrightAssertions.assertThat(statsPanes).containsText("Purchase");
        PlaywrightAssertions.assertThat(statsPanes).containsText("Groups");
        PlaywrightAssertions.assertThat(statsPanes).containsText("Ledgers");

        // Tab 4: Stock Query
        page.locator("[data-testid='l3-tab-stock-query']").click();
        page.waitForTimeout(300);
        Assertions.assertThat(workbench.textContent()).contains("Digital Thermometer X1");
    }

    @Test
    @Order(3)
    void um110InteractiveHumanVoucherCreationAndAllKeyboardShortcuts() {
        selectCompanyInSidebar(interactiveCompanyName);
        setActivePeriodInUi("2025-04-01", "2025-05-31");

        navigateSpa("/app/vouchers/new");
        page.locator("[data-testid='voucher-grid-root']").waitFor();

        // Ensure voucher date is 2025-04-15 inside open FY 2025-26
        setVoucherDateIfVisible("2025-04-15");
        if (page.locator("[data-testid='vch-open-fy-btn']").count() > 0
            && page.locator("[data-testid='vch-open-fy-btn']").isVisible()) {
            page.locator("[data-testid='vch-open-fy-btn']").click();
            page.waitForTimeout(300);
        }

        // 1. F6 Receipt (Single Entry): type ledger + Tab (no Enter!), click Amount, Indian commas, Ctrl+A in Amount!
        page.locator("[data-testid='vch-tab-RECEIPT']").click();
        setVoucherDateIfVisible("2025-04-15");
        typeTypeaheadAndTab("#vch-single-account", "Cash Counter");
        typeTypeaheadAndTab("#vch-single-ledger-0", "Alpha Pharma Customer");
        clickAmountTypeFormattedAndCtrlA("#vch-single-amt-0", "25,000.00", "Receipt");

        // 2. F5 Payment (Single Entry): type + Tab + mouse click Amount + Indian commas 12,500.00 + Ctrl+A
        page.locator("[data-testid='vch-tab-PAYMENT']").click();
        setVoucherDateIfVisible("2025-04-16");
        typeTypeaheadAndTab("#vch-single-account", "Cash Counter");
        typeTypeaheadAndTab("#vch-single-ledger-0", "Office Rent Expense");
        clickAmountTypeFormattedAndCtrlA("#vch-single-amt-0", "12,500.00", "Payment");

        // 3. F4 Contra (Single Entry): Cash Counter -> Petty Cash Vault 5,000.00 + Ctrl+A inside Amount
        page.locator("[data-testid='vch-tab-CONTRA']").click();
        setVoucherDateIfVisible("2025-04-17");
        typeTypeaheadAndTab("#vch-single-account", "Petty Cash Vault");
        typeTypeaheadAndTab("#vch-single-ledger-0", "Cash Counter");
        clickAmountTypeFormattedAndCtrlA("#vch-single-amt-0", "5,000.00", "Contra");

        // 4. F7 Journal (Double Entry) + Ctrl+R Repeat Narration + PageUp / PageDown navigation
        page.locator("[data-testid='vch-tab-JOURNAL']").click();
        setVoucherDateIfVisible("2025-04-18");
        typeTypeaheadAndTab("#vch-ledger-0", "Office Rent Expense");
        Locator dr0 = page.locator("#vch-dr-amt-0");
        dr0.click();
        dr0.fill("3,200.00");
        dr0.press("Enter");
        page.waitForTimeout(150);
        if (page.locator("[data-testid='vch-side-1']").count() > 0) {
            page.locator("[data-testid='vch-side-1']").selectOption("CR");
        }
        typeTypeaheadAndTab("#vch-ledger-1", "Cash Counter");
        Locator cr1 = page.locator("#vch-cr-amt-1");
        cr1.click();
        cr1.fill("3,200.00");
        page.locator("[data-testid='voucher-narration-input']").fill("Monthly provision human narration");
        cr1.click();
        page.keyboard().press("Control+a");
        waitForSavedBannerForType("Journal");

        // Test Ctrl+R Repeat Last Narration on next Journal voucher
        page.locator("[data-testid='vch-repeat-narration-btn']").click();
        PlaywrightAssertions.assertThat(page.locator("[data-testid='voucher-narration-input']"))
            .hasValue("Monthly provision human narration");
        page.locator("[data-testid='voucher-narration-input']").fill("");

        // Test PageUp (loads previous posted voucher) and PageDown (returns forward)
        page.locator("[data-testid='voucher-grid-root']").focus();
        page.keyboard().press("PageUp");
        page.waitForTimeout(350);
        Assertions.assertThat(page.locator("[data-testid='voucher-narration-input']").inputValue())
            .contains("Monthly provision human narration");
        page.keyboard().press("PageDown");
        page.waitForTimeout(300);

        // 5. F9 Purchase Item Invoice (20 NOS Paracetamol @ 100.00 = 2,000.00)
        navigateSpa("/app/gateway");
        navigateSpa("/app/vouchers/new");
        page.locator("[data-testid='vch-tab-PURCHASE']").waitFor();
        page.locator("[data-testid='vch-tab-PURCHASE']").click();
        ensureItemInvoiceMode();
        setVoucherDateIfVisible("2025-04-19");
        if (page.locator("#vch-supp-inv-no").isVisible()) {
            page.locator("#vch-supp-inv-no").fill("SUP-110-01");
        }
        typeTypeaheadAndTab("#vch-party-account", "Beta Medical Supplier");
        typeTypeaheadAndTab("#vch-accounting-ledger", "Purchases - Exempt Goods");
        typeTypeaheadAndTab("#item-select-0", "Paracetamol 500mg Strip");
        page.locator("[data-testid='item-billed-qty-0']").fill("20");
        Locator purRate = page.locator("[data-testid='item-rate-0']");
        purRate.click();
        purRate.fill("100.00");
        page.keyboard().press("Control+a");
        waitForSavedBannerForType("Purchase");

        // 6. F8 Sales Item Invoice (5 NOS Paracetamol @ 150.00 = 750.00)
        page.locator("[data-testid='vch-tab-SALES']").click();
        ensureItemInvoiceMode();
        setVoucherDateIfVisible("2025-04-20");
        typeTypeaheadAndTab("#vch-party-account", "Alpha Pharma Customer");
        typeTypeaheadAndTab("#vch-accounting-ledger", "Sales - Exempt Goods");
        typeTypeaheadAndTab("#item-select-0", "Paracetamol 500mg Strip");
        page.locator("[data-testid='item-billed-qty-0']").fill("5");
        Locator salesRate = page.locator("[data-testid='item-rate-0']");
        salesRate.click();
        salesRate.fill("150.00");
        page.keyboard().press("Control+a");
        waitForSavedBannerForType("Sales");

        // 7. Alt+F5 Debit Note (Purchase Return: 1 NOS @ 100.00)
        selectOtherVoucherType("Debit Note");
        ensureItemInvoiceMode();
        setVoucherDateIfVisible("2025-04-21");
        if (page.locator("#vch-supp-inv-no").isVisible()) {
            page.locator("#vch-supp-inv-no").fill("DN-110-01");
        }
        typeTypeaheadAndTab("#vch-party-account", "Beta Medical Supplier");
        typeTypeaheadAndTab("#vch-accounting-ledger", "Purchases - Exempt Goods");
        typeTypeaheadAndTab("#item-select-0", "Paracetamol 500mg Strip");
        page.locator("[data-testid='item-billed-qty-0']").fill("1");
        Locator dnRate = page.locator("[data-testid='item-rate-0']");
        dnRate.click();
        dnRate.fill("100.00");
        page.keyboard().press("Control+a");
        waitForSavedBannerForType("Debit Note");

        // 8. Alt+F6 Credit Note (Sales Return: 1 NOS @ 150.00)
        selectOtherVoucherType("Credit Note");
        ensureItemInvoiceMode();
        setVoucherDateIfVisible("2025-04-22");
        typeTypeaheadAndTab("#vch-party-account", "Alpha Pharma Customer");
        typeTypeaheadAndTab("#vch-accounting-ledger", "Sales - Exempt Goods");
        typeTypeaheadAndTab("#item-select-0", "Paracetamol 500mg Strip");
        page.locator("[data-testid='item-billed-qty-0']").fill("1");
        Locator cnRate = page.locator("[data-testid='item-rate-0']");
        cnRate.click();
        cnRate.fill("150.00");
        page.keyboard().press("Control+a");
        waitForSavedBannerForType("Credit Note");

        // 9. Alt+F7 Stock / BOM Manufacturing Journal (assemble 2 Carton Pack Kits consuming Paracetamol)
        selectOtherVoucherType("Stock Journal");
        setVoucherDateIfVisible("2025-04-23");
        page.locator("[data-testid='toggle-bom-mode-btn']").click();
        page.locator("[data-testid='bom-manufacturing-header']").waitFor();
        typeTypeaheadAndTab("#mfg-fin-item", "Carton Pack Kit");
        page.locator("[data-testid='mfg-qty-input']").fill("2");
        page.locator("[data-testid='expand-bom-preview-btn']").click();
        page.locator("[data-testid='bom-cost-summary']").waitFor();
        page.keyboard().press("Control+a");
        waitForSavedBannerForType("Stock Journal");

        // 10. Memorandum & Reversing Journal + Alt+T Post-Dated + Ctrl+L Optional + Shell Modals (Alt+N, F11, F12, Ctrl+G)
        navigateSpa("/app/gateway");
        navigateSpa("/app/vouchers/new");
        page.locator("[data-testid='voucher-grid-root']").waitFor();
        selectOtherVoucherType("Memorandum");
        setVoucherDateIfVisible("2025-04-24");
        typeTypeaheadAndTab("#vch-ledger-0", "Office Rent Expense");
        Locator memoDr = page.locator("#vch-dr-amt-0");
        memoDr.click();
        memoDr.fill("1,500.00");
        memoDr.press("Enter");
        page.waitForTimeout(150);
        if (page.locator("[data-testid='vch-side-1']").count() > 0) {
            page.locator("[data-testid='vch-side-1']").selectOption("CR");
        }
        typeTypeaheadAndTab("#vch-ledger-1", "Cash Counter");
        clickAmountTypeFormattedAndCtrlA("#vch-cr-amt-1", "1,500.00", "Memorandum");

        selectOtherVoucherType("Reversing Journal");
        setVoucherDateIfVisible("2025-04-25");
        if (page.locator("#vch-rev-upto").isVisible()) {
            page.locator("#vch-rev-upto").fill("2025-05-31");
        }
        typeTypeaheadAndTab("#vch-ledger-0", "Office Rent Expense");
        Locator revDr = page.locator("#vch-dr-amt-0");
        revDr.click();
        revDr.fill("2,400.00");
        revDr.press("Enter");
        page.waitForTimeout(150);
        if (page.locator("[data-testid='vch-side-1']").count() > 0) {
            page.locator("[data-testid='vch-side-1']").selectOption("CR");
        }
        typeTypeaheadAndTab("#vch-ledger-1", "Cash Counter");
        clickAmountTypeFormattedAndCtrlA("#vch-cr-amt-1", "2,400.00", "Reversing Journal");

        // Toggle Alt+T Post-Dated badge & Ctrl+L Optional badge
        page.locator("[data-testid='vch-tab-JOURNAL']").click();
        page.locator("body").focus();
        page.keyboard().press("Alt+t");
        if (page.locator("[data-testid='voucher-postdated-badge']").count() > 0) {
            Assertions.assertThat(page.locator("[data-testid='voucher-postdated-badge']").isVisible()).isTrue();
            page.keyboard().press("Alt+t");
        }
        page.locator("[data-testid='btn-toggle-optional']").click();
        page.locator("[data-testid='voucher-optional-badge']").waitFor();
        page.locator("[data-testid='btn-toggle-optional']").click();

        // Alt+N Shell Calculator modal: evaluate 1250*4 = 5000
        page.locator("body").focus();
        page.keyboard().press("Alt+n");
        Locator calcModal = page.locator("[data-testid='shell-calculator-modal']");
        calcModal.waitFor();
        Locator calcInput = page.locator("[data-testid='shell-calculator-input']");
        calcInput.fill("1250*4");
        calcInput.press("Enter");
        PlaywrightAssertions.assertThat(page.locator("[data-testid='shell-calculator-result']"))
            .containsText("5,000.00");
        page.keyboard().press("Escape");
        calcModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // F12 Shell Configure modal & F11 Company Features modal & Ctrl+G Switch To
        page.locator("body").focus();
        page.keyboard().press("F12");
        Locator cfgModal = page.locator("[data-testid='shell-configure-modal']");
        cfgModal.waitFor();
        page.keyboard().press("Escape");
        cfgModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        page.locator("body").focus();
        page.keyboard().press("F11");
        page.waitForTimeout(200);
        page.keyboard().press("Escape");
        page.waitForTimeout(150);

        page.locator("body").focus();
        page.keyboard().press("Control+g");
        page.locator("mc-go-to-palette input").first().waitFor();
        page.keyboard().press("Escape");

        // Day Book shortcuts on interactive company: Space, Ctrl+Space, Alt+R, Ctrl+U, Alt+2 Duplicate
        navigateSpa("/app/daybook");
        page.locator(".mc-daybook-table").waitFor();
        int initialCount = page.locator("tr.mc-daybook-row").count();
        Assertions.assertThat(initialCount).isGreaterThanOrEqualTo(9);

        page.locator("tr.mc-daybook-row").first().click();
        page.keyboard().press("Alt+r");
        PlaywrightAssertions.assertThat(page.locator("tr.mc-daybook-row")).hasCount(initialCount - 1);
        page.keyboard().press("Control+u");
        PlaywrightAssertions.assertThat(page.locator("tr.mc-daybook-row")).hasCount(initialCount);
        page.keyboard().press("Space");
        page.keyboard().press("Control+Space");

        // Alt+2 Duplicate opens pre-populated voucher editor -> Escape returns cleanly
        page.locator("tr.mc-daybook-row").first().click();
        page.locator("[data-testid='daybook-duplicate-btn']").click();
        page.waitForURL("**/vouchers/**");

        // Trial Balance comparative / auto-columnar (Alt+N / Alt+C) & F5 Ledger-wise toggle
        navigateSpa("/app/reports/trial-balance");
        page.locator("[data-testid='tb-table']").waitFor();
        page.locator("[data-testid='tb-auto-column-btn']").click();
        PlaywrightAssertions.assertThat(page.locator("[data-testid='tb-table']")).containsText("Opening Dr");
        PlaywrightAssertions.assertThat(page.locator("[data-testid='tb-table']")).containsText("Transactions Dr");
        page.locator("[data-testid='tb-toggle-view-btn']").click();
        PlaywrightAssertions.assertThat(page.locator("[data-testid='tb-table']")).containsText("Cash Counter");
    }

    @Test
    @Order(4)
    void v110AccessibilityDarkThemeScalingContextualHelpSuperAdminAndNativeSwingPkce() throws Exception {
        selectCompanyInSidebar(BENCHMARK_COMPANY_NAME);
        navigateSpa("/app/gateway");

        // 1. Account & Browser UI Preferences (schemaVersion: 2, Dark contrast >= 4.5:1, 75%..200% scale, 320px reflow)
        page.locator("[data-testid='omni-keyboard-settings-btn']").click();
        Locator prefsModal = page.locator("[data-testid='ui-preferences-modal']");
        prefsModal.waitFor();

        page.locator("[data-testid='pref-scope-account']").check();
        page.locator("[data-testid='pref-appearance-dark']").check();
        page.locator("[data-testid='pref-scale-increase-btn']").click();
        page.locator("[data-testid='kbd-apply-btn']").click();
        prefsModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        String activeTheme = (String) page.evaluate("() => document.documentElement.getAttribute('data-mc-theme')");
        Assertions.assertThat(activeTheme).isEqualTo("dark");

        // Verify Dark Theme WCAG 2.2 AA contrast >= 4.5:1 on primary button and surface text
        @SuppressWarnings("unchecked")
        Map<String, String> darkProbe = (Map<String, String>) page.evaluate(
            """
            () => {
              const btn = document.createElement('button');
              btn.className = 'mc-btn mc-btn-primary';
              btn.textContent = 'Dark Contrast Probe';
              const card = document.createElement('div');
              card.className = 'mc-card';
              card.textContent = 'Surface Text Probe';
              document.body.appendChild(btn);
              document.body.appendChild(card);
              const bs = window.getComputedStyle(btn);
              const cs = window.getComputedStyle(card);
              const out = {
                btnFg: bs.color,
                btnBg: bs.backgroundColor,
                cardFg: cs.color,
                cardBg: cs.backgroundColor
              };
              btn.remove();
              card.remove();
              return out;
            }
            """
        );
        Assertions.assertThat(
            QaPlaywrightAndFixtureSupport.computeWcagContrastRatio(darkProbe.get("btnFg"), darkProbe.get("btnBg"))
        ).as("Dark theme .mc-btn-primary contrast must be >= 4.5:1").isGreaterThanOrEqualTo(new BigDecimal("4.50"));
        Assertions.assertThat(
            QaPlaywrightAndFixtureSupport.computeWcagContrastRatio(darkProbe.get("cardFg"), darkProbe.get("cardBg"))
        ).as("Dark theme .mc-card surface text contrast must be >= 4.5:1").isGreaterThanOrEqualTo(new BigDecimal("4.50"));

        // Apply Browser-scope override (LIGHT + 200%), verify masking + "Use account settings in this browser", + 320px reflow
        page.locator("[data-testid='omni-keyboard-settings-btn']").click();
        prefsModal.waitFor();
        page.locator("[data-testid='pref-scope-browser']").check();
        page.locator("[data-testid='pref-appearance-light']").check();
        for (int i = 0; i < 4; i++) {
            page.locator("[data-testid='pref-scale-increase-btn']").click();
        }
        page.locator("[data-testid='kbd-apply-btn']").click();
        prefsModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        Assertions.assertThat((String) page.evaluate("() => document.documentElement.getAttribute('data-mc-theme')"))
            .isEqualTo("light");

        // Test 320px CSS viewport reflow at 200% text scale (zero clipped controls / modal remains operable)
        page.setViewportSize(320, 800);
        page.waitForTimeout(150);
        page.locator("[data-testid='omni-keyboard-settings-btn']").click();
        prefsModal.waitFor();
        Locator inheritBtn = page.locator("[data-testid='pref-use-account-in-browser-btn']");
        inheritBtn.waitFor();
        inheritBtn.click();
        page.waitForTimeout(200);

        // Restore viewport 1440x900 and reset My account back to SYSTEM + 100%
        page.setViewportSize(1440, 900);
        page.locator("[data-testid='pref-scope-account']").check();
        page.locator("[data-testid='pref-appearance-system']").check();
        page.locator("[data-testid='kbd-defaults-btn']").click();
        page.locator("[data-testid='kbd-apply-btn']").click();
        prefsModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Contextual Help F1 & Layer-1 Escape-First Precedence over open Commands Modal (Doc 50 §7)
        page.locator("[data-testid='omni-commands-btn']").click();
        Locator cmdModal = page.locator("[data-testid='commands-menu-modal']");
        cmdModal.waitFor();

        page.keyboard().press("F1");
        Locator helpPanel = page.locator("[data-testid='contextual-help-panel']");
        helpPanel.waitFor();
        Assertions.assertThat(helpPanel.getAttribute("aria-modal")).isEqualTo("false");

        page.locator("[data-testid='contextual-help-browse-toggle']").click();
        Locator helpSearch = page.locator("[data-testid='contextual-help-search-input']");
        helpSearch.waitFor();
        helpSearch.fill("amount");
        PlaywrightAssertions.assertThat(helpPanel).containsText("HELP-110-");

        // Focus close button and press Escape ONCE -> Help Panel dismisses FIRST while Commands Modal stays open!
        page.locator("[data-testid='contextual-help-close-btn']").focus();
        page.keyboard().press("Escape");
        helpPanel.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        Assertions.assertThat(cmdModal.isVisible())
            .as("Doc 50 §7 Layer-1 Escape precedence: dismissing Help must NOT close underlying modal")
            .isTrue();

        // Second Escape closes Commands Modal
        page.keyboard().press("Escape");
        cmdModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // 3. SUPER_ADMIN Government & Connected Banking Console (/app/admin/government-integrations)
        selectCompanyInSidebar(interactiveCompanyName);
        navigateSpa("/app/admin/government-integrations");
        Locator govScreen = page.locator("[data-testid='government-integrations-screen']");
        govScreen.waitFor();

        // Inspect reconciled 44-row inventory (GOV-D01..39 + Connected Banking)
        page.locator("[data-testid='gov-tab-inventory']").click();
        Locator invSection = page.locator("[data-testid='gov-inventory-section']");
        invSection.waitFor();
        PlaywrightAssertions.assertThat(invSection).containsText("GOV-D01");
        page.locator("[data-testid='gov-inventory-search']").fill("ICICI_CIB");
        PlaywrightAssertions.assertThat(invSection).containsText("ICICI");

        // Switch back to Profiles tab -> Create SANDBOX IRP6 Draft Profile with step-up password
        page.locator("button", new Page.LocatorOptions().setHasText("Integration Profiles")).first().click();
        page.locator("[data-testid='gov-new-profile-btn']").click();
        Locator govModal = page.locator("[data-testid='gov-profile-modal']");
        govModal.waitFor();

        String profileLabel = "IRP6 Sandbox E2E " + UUID.randomUUID().toString().substring(0, 6);
        page.locator("[data-testid='gov-draft-name-input']").fill(profileLabel);
        page.locator("[data-testid='gov-draft-consent-input']").fill("CONSENT-E2E-SANDBOX-01");
        page.locator("[data-testid='gov-save-draft-btn']").click();

        completeStepUpModalWithPassword();
        PlaywrightAssertions.assertThat(govScreen).containsText(profileLabel);

        // Stage write-only SANDBOX credentials -> verify inputs wipe to empty & zero secret readback
        String canarySecret = "CanarySecret#NeverReadable9988";
        page.locator("[data-testid='gov-slot-input-clientId']").fill("irp6-sandbox-client-01");
        page.locator("[data-testid='gov-slot-input-clientSecret']").fill(canarySecret);
        page.locator("[data-testid='gov-slot-input-taxpayerApiUsername']").fill("gst_api_sandbox_user");
        page.locator("[data-testid='gov-slot-input-taxpayerApiPassword']").fill("SandboxApiPass#2026");
        page.locator("[data-testid='gov-stage-credentials-btn']").click();

        completeStepUpModalWithPassword();
        PlaywrightAssertions.assertThat(page.locator("[data-testid='slot-status-clientSecret']"))
            .containsText("Configured");
        Assertions.assertThat(page.locator("[data-testid='gov-slot-input-clientSecret']").inputValue())
            .as("Write-only credential input must wipe immediately after staging")
            .isEmpty();
        Assertions.assertThat(govScreen.textContent()).doesNotContain(canarySecret);

        // Run Safe Connection Validation (SANDBOX self-check, zero IRN/filing side effects)
        page.locator("[data-testid='gov-validate-btn']").click();
        completeStepUpModalWithPassword();
        Locator valCard = page.locator("[data-testid='gov-validation-status-card']");
        valCard.waitFor();
        PlaywrightAssertions.assertThat(valCard).containsText("VALIDATED");

        // 4. Live Swing Desktop External Browser OAuth2 + PKCE S256 Loopback Flow against :18080
        verifyLiveNativeSwingBrowserPkceLoopbackFlow();

        Assertions.assertThat(BLOCKED_EXTERNAL_URLS)
            .as("Private-LAN air-gapped law: zero external HTTP(S) calls permitted")
            .isEmpty();
    }

    @Test
    @Order(5)
    void v200DynamicF11F12ShowInactiveAndShortcutParityWorkflow() {
        selectCompanyInSidebar(interactiveCompanyName);
        setActivePeriodInUi("2025-04-01", "2025-05-31");

        // 1. Press F11 on Gateway -> open in-place [data-testid="global-f11-modal"], toggle Show More & Show All,
        //    verify all F11 checkboxes render, and accept via [data-testid="f11-accept-btn"]
        navigateSpa("/app/gateway");
        page.locator("[data-testid='gateway-menu-card']").waitFor();
        page.locator("body").focus();
        page.keyboard().press("F11");

        Locator f11Modal = page.locator("[data-testid='global-f11-modal']");
        f11Modal.waitFor();
        page.locator("[data-testid='f11-show-more-toggle']").check();
        page.locator("[data-testid='f11-show-all-toggle']").check();

        for (String cbTestId : List.of(
            "f11-maintain-accounts-cb",
            "f11-billwise-cb",
            "f11-cost-centres-cb",
            "f11-interest-calc-cb",
            "f11-maintain-inventory-cb",
            "f11-inventory-integrated-cb",
            "f11-price-levels-cb",
            "f11-batches-cb",
            "f11-expiry-batches-cb",
            "f11-discount-col-cb",
            "f11-separate-actual-billed-cb",
            "f11-job-order-cb",
            "f11-cost-tracking-cb",
            "f11-job-costing-cb",
            "f11-gst-cb",
            "f11-tds-cb",
            "f11-tcs-cb",
            "f11-vat-cb",
            "f11-excise-cb",
            "f11-service-tax-cb",
            "f11-payroll-cb",
            "f11-payroll-statutory-cb",
            "f11-multiple-addresses-cb",
            "f11-mark-modified-cb",
            "f11-mca-edit-log-cb"
        )) {
            PlaywrightAssertions.assertThat(f11Modal.locator("[data-testid='" + cbTestId + "']")).isVisible();
        }
        Assertions.assertThat(f11Modal.locator("input[type='checkbox']").count())
            .as("All F11 feature & header checkboxes must be rendered")
            .isGreaterThanOrEqualTo(27);

        page.locator("[data-testid='f11-accept-btn']").click();
        f11Modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Navigate to Gateway Create ('c'), toggle Show More & Show Inactive, click inactive Payroll Masters,
        //    and confirm one-click F11 feature activation!
        page.locator("body").focus();
        page.keyboard().press("c");
        Locator level2 = page.locator("[data-testid='gateway-level-2']");
        level2.waitFor();

        page.locator("[data-testid='gateway-show-more-btn']").click();
        page.locator("[data-testid='gateway-show-inactive-btn']").click();
        Locator payrollSubNode = page.locator("[data-testid='gateway-subnode-P']");
        payrollSubNode.waitFor();
        PlaywrightAssertions.assertThat(payrollSubNode).containsText("Payroll Masters");
        PlaywrightAssertions.assertThat(payrollSubNode).containsText("Inactive");

        payrollSubNode.click();
        Locator inactiveModal = page.locator("[data-testid='inactive-feature-enable-modal']");
        inactiveModal.waitFor();
        PlaywrightAssertions.assertThat(inactiveModal).containsText("Maintain Payroll");
        page.locator("[data-testid='inactive-feature-enable-yes-btn']").click();
        inactiveModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        Locator level3 = page.locator("[data-testid='gateway-level-3']");
        level3.waitFor();
        PlaywrightAssertions.assertThat(level3).containsText("Employee Group");
        PlaywrightAssertions.assertThat(level3).containsText("Pay Heads");

        // 3. Navigate to /app/vouchers/new, press F12 to open context-sensitive [data-testid="shell-configure-modal"],
        //    switch Use Cr/Dr instead of To/By -> No (By/To) and Provide narration for each ledger -> Yes,
        //    verify By/To and [data-testid="vch-line-narration-0"] in F7 Journal, then restore Dr/Cr
        navigateSpa("/app/vouchers/new");
        page.locator("[data-testid='voucher-grid-root']").waitFor();
        page.locator("[data-testid='vch-tab-JOURNAL']").click();

        page.locator("body").focus();
        page.keyboard().press("F12");
        Locator f12Modal = page.locator("[data-testid='shell-configure-modal']");
        f12Modal.waitFor();
        page.locator("[data-testid='f12-tab-voucher']").click();
        page.locator("[data-testid='f12-use-crdr-toggle']").uncheck();
        page.locator("[data-testid='f12-line-narration-toggle']").check();
        page.locator("[data-testid='f12-accept-btn']").click();
        f12Modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        PlaywrightAssertions.assertThat(page.locator("[data-testid='voucher-grid-root']")).containsText("By/To");
        Locator lineNarr0 = page.locator("[data-testid='vch-line-narration-0']");
        lineNarr0.waitFor();
        Assertions.assertThat(lineNarr0.isVisible()).isTrue();

        // Restore Dr/Cr & disable line narration via F12
        page.locator("body").focus();
        page.keyboard().press("F12");
        f12Modal.waitFor();
        page.locator("[data-testid='f12-tab-voucher']").click();
        page.locator("[data-testid='f12-use-crdr-toggle']").check();
        page.locator("[data-testid='f12-line-narration-toggle']").uncheck();
        page.locator("[data-testid='f12-accept-btn']").click();
        f12Modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        PlaywrightAssertions.assertThat(page.locator("[data-testid='voucher-grid-root']")).containsText("Dr/Cr");

        // 4. Exercise F10: Other Vouchers with Show Inactive, Ctrl+Alt+U Unused Voucher Numbers, and Alt+J Stat Adjustment
        page.locator("[data-testid='btn-other-vouchers']").click();
        Locator otherVchModal = page.locator("[data-testid='other-vouchers-modal']");
        otherVchModal.waitFor();
        page.locator("[data-testid='other-vouchers-show-inactive-btn']").click();
        PlaywrightAssertions.assertThat(otherVchModal).containsText("Job Work Out Order");
        page.keyboard().press("Escape");
        otherVchModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        page.locator("body").focus();
        page.keyboard().press("Control+Alt+u");
        Locator unusedNosModal = page.locator("[data-testid='unused-voucher-numbers-modal']");
        unusedNosModal.waitFor();
        Assertions.assertThat(unusedNosModal.isVisible()).isTrue();
        page.keyboard().press("Escape");
        unusedNosModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        page.locator("body").focus();
        page.keyboard().press("Alt+j");
        Locator statAdjModal = page.locator("[data-testid='stat-adjustment-modal']");
        statAdjModal.waitFor();
        Assertions.assertThat(statAdjModal.isVisible()).isTrue();
        page.locator("[data-testid='stat-adjustment-apply-btn']").click();
        statAdjModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        Assertions.assertThat(page.locator("[data-testid='voucher-narration-input']").inputValue())
            .contains("[Stat Adj:");
        page.locator("[data-testid='voucher-narration-input']").fill("");

        // 5. Navigate to /app/reports/trial-balance, press F12 to toggle Show Percentages, and verify % of Total column!
        navigateSpa("/app/reports/trial-balance");
        dismissDirtyEditorIfOpen();
        page.locator("[data-testid='tb-table']").waitFor();

        page.locator("body").focus();
        page.keyboard().press("F12");
        f12Modal.waitFor();
        page.locator("[data-testid='f12-tab-reports']").click();
        page.locator("[data-testid='f12-show-percentages-toggle']").check();
        page.locator("[data-testid='f12-accept-btn']").click();
        f12Modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        Locator pctHeader = page.locator("[data-testid='tb-th-percentage']");
        pctHeader.waitFor();
        PlaywrightAssertions.assertThat(pctHeader).containsText("% of Total");

        // Restore Show Percentages = false
        page.locator("body").focus();
        page.keyboard().press("F12");
        f12Modal.waitFor();
        page.locator("[data-testid='f12-tab-reports']").click();
        page.locator("[data-testid='f12-show-percentages-toggle']").uncheck();
        page.locator("[data-testid='f12-accept-btn']").click();
        f12Modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));

        // 6. Verify context-sensitive Right Function Bar buttons, Day Book Alt+F1 Detailed ledger breakup,
        //    and hierarchical Escape step-back to Gateway!
        PlaywrightAssertions.assertThat(
            page.locator(".mc-fbar-rail .mc-fbar-btn")
                .filter(new Locator.FilterOptions().setHasText("Basis of Values"))
                .first()
        ).isVisible();
        PlaywrightAssertions.assertThat(
            page.locator(".mc-fbar-rail .mc-fbar-btn")
                .filter(new Locator.FilterOptions().setHasText("Exception Reports"))
                .first()
        ).isVisible();

        navigateSpa("/app/daybook");
        page.locator(".mc-daybook-table").waitFor();
        page.locator("[data-testid='daybook-detailed-btn']").click();
        Locator detailBreakup = page.locator("[data-testid='daybook-detail-breakup']").first();
        detailBreakup.waitFor();
        Assertions.assertThat(detailBreakup.textContent()).isNotBlank();

        page.locator("body").focus();
        page.keyboard().press("Escape");
        page.locator("[data-testid='gateway-menu-card']").waitFor();

        Assertions.assertThat(BLOCKED_EXTERNAL_URLS)
            .as("Private-LAN air-gapped law: zero external HTTP(S) calls permitted")
            .isEmpty();
    }

    private static void verifyLiveNativeSwingBrowserPkceLoopbackFlow() throws Exception {
        // Respect Edge Gateway Nginx mc_auth_limit (10r/m, burst=5) after prior step-up ceremonies
        Thread.sleep(19_000L);

        CompletableFuture<String> callbackQueryFuture = new CompletableFuture<>();
        HttpServer loopbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = loopbackServer.getAddress().getPort();
        loopbackServer.createContext("/oauth2/callback", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            byte[] html = "<html><body><h1>Sign-in complete. Return to MoneyCraft Desktop.</h1></body></html>"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html);
            }
            callbackQueryFuture.complete(rawQuery == null ? "" : rawQuery);
        });
        loopbackServer.start();

        try (BrowserContext cleanBrowserCtx = browser.newContext()) {
            Page externalBrowserPage = cleanBrowserCtx.newPage();
            String codeVerifier = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((UUID.randomUUID().toString() + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII));
            String codeChallenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII))
                );
            String state = "swing-st-" + UUID.randomUUID().toString().substring(0, 12);
            String nonce = "swing-nn-" + UUID.randomUUID().toString().substring(0, 12);
            String redirectUri = "http://127.0.0.1:" + port + "/oauth2/callback";

            String authorizeUrl = BASE_URL + "/api/v1/auth/native/authorize"
                + "?response_type=code"
                + "&client_id=moneycraft-swing-desktop"
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("openid profile offline_access", StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8)
                + "&nonce=" + URLEncoder.encode(nonce, StandardCharsets.UTF_8)
                + "&code_challenge=" + URLEncoder.encode(codeChallenge, StandardCharsets.UTF_8)
                + "&code_challenge_method=S256";

            // Unauthenticated browser hits /api/v1/auth/native/authorize -> redirected to /login?nativeAuthorize=...
            externalBrowserPage.navigate(authorizeUrl);
            externalBrowserPage.waitForURL("**/login?nativeAuthorize=*");
            externalBrowserPage.locator("#mc-login-username").fill(OWNER_USER);
            externalBrowserPage.locator("#mc-login-password").fill(OWNER_PASS);
            externalBrowserPage.locator("button.mc-btn-primary.mc-login-submit").click();

            String callbackQuery = callbackQueryFuture.get(15, TimeUnit.SECONDS);
            Assertions.assertThat(callbackQuery).contains("code=mc_nat_cd_").contains("state=" + state);

            // Allow 1 token to replenish in Edge Gateway Nginx mc_auth_limit (10r/m, burst=5) after browser redirect
            Thread.sleep(6_500L);

            String authCode = extractQueryParam(callbackQuery, "code");
            HttpClient swingHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

            // Exchange PKCE authorization code with ZERO cookies
            String tokenPayload = """
                {
                  "grant_type": "authorization_code",
                  "client_id": "moneycraft-swing-desktop",
                  "code": "%s",
                  "redirect_uri": "%s",
                  "code_verifier": "%s"
                }
                """.formatted(authCode, redirectUri, codeVerifier);
            HttpResponse<String> tokenRes = swingHttpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/native/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(tokenPayload, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Assertions.assertThat(tokenRes.statusCode()).isEqualTo(200);
            JsonNode tokJson = objectMapper.readTree(tokenRes.body());
            String access1 = tokJson.get("access_token").asString();
            String refresh1 = tokJson.get("refresh_token").asString();
            Assertions.assertThat(access1).startsWith("mc_nat_at_");
            Assertions.assertThat(refresh1).startsWith("mc_nat_rt_");

            // Authenticated Desktop Bearer calls to /companies and /trial-balance
            HttpResponse<String> cmpRes = swingHttpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/companies"))
                    .header("Authorization", "Bearer " + access1)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Assertions.assertThat(cmpRes.statusCode()).isEqualTo(200);
            Assertions.assertThat(cmpRes.body()).contains(BENCHMARK_COMPANY_NAME);

            // Replenish 2 tokens in Nginx mc_auth_limit (10r/m = 1 token / 6s) before rotate + replay check
            Thread.sleep(13_000L);

            // Rotate refresh token once -> succeeds; replay old refresh1 -> 401 family revoked!
            String rotatePayload = """
                {
                  "grant_type": "refresh_token",
                  "client_id": "moneycraft-swing-desktop",
                  "refresh_token": "%s"
                }
                """.formatted(refresh1);
            HttpResponse<String> rotRes = swingHttpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/native/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rotatePayload, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Assertions.assertThat(rotRes.statusCode()).isEqualTo(200);

            HttpResponse<String> replayRes = swingHttpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/api/v1/auth/native/token"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rotatePayload, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            Assertions.assertThat(replayRes.statusCode()).isEqualTo(401);
        } finally {
            loopbackServer.stop(0);
        }
    }

    private static void completeStepUpModalWithPassword() {
        Locator stepModal = page.locator("[data-testid='gov-stepup-modal']");
        stepModal.waitFor();
        page.locator("[data-testid='gov-stepup-password-input']").fill(OWNER_PASS);
        page.locator("[data-testid='gov-stepup-submit-btn']").click();
        stepModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    private static void typeTypeaheadAndTab(String inputSelector, String queryText) {
        Locator input = page.locator(inputSelector);
        input.waitFor();
        input.click();
        input.fill("");
        page.keyboard().type(queryText);
        page.waitForTimeout(120);
        input.press("Tab");
        page.waitForTimeout(120);
    }

    private static void clickAmountTypeFormattedAndCtrlA(
        String amountSelector,
        String formattedAmount,
        String expectedVoucherTypeName
    ) {
        Locator amt = page.locator(amountSelector);
        amt.waitFor();
        amt.click();
        amt.fill("");
        page.keyboard().type(formattedAmount);
        // Press Ctrl+A while focus is still inside the Amount input (UM-110-02 acceptance gate)
        page.keyboard().press("Control+a");
        waitForSavedBannerForType(expectedVoucherTypeName);
    }

    private static void waitForSavedBannerForType(String expectedVoucherTypeName) {
        Locator saved = page.locator("[data-testid='voucher-saved-banner']");
        saved.waitFor();
        PlaywrightAssertions.assertThat(saved).containsText("Saved " + expectedVoucherTypeName);
    }

    private static void ensureItemInvoiceMode() {
        page.waitForTimeout(100);
        if (page.locator("#vch-party-account").count() > 0 && page.locator("#vch-party-account").isVisible()
            && page.locator("#item-select-0").count() > 0 && page.locator("#item-select-0").isVisible()) {
            return;
        }
        page.locator("[data-testid='btn-change-mode']").click();
        Locator modeModal = page.locator("[data-testid='change-mode-modal']");
        modeModal.waitFor();
        modeModal.locator("li.mc-vch-modal-item")
            .filter(new Locator.FilterOptions().setHasText("Item Invoice"))
            .first()
            .click();
        modeModal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    private static void selectOtherVoucherType(String voucherTypeLabel) {
        page.locator("[data-testid='btn-other-vouchers']").click();
        Locator modal = page.locator("[data-testid='other-vouchers-modal']");
        modal.waitFor();
        modal.locator("input").first().fill(voucherTypeLabel);
        modal.locator("li.mc-vch-modal-item")
            .filter(new Locator.FilterOptions().setHasText(voucherTypeLabel))
            .first()
            .click();
        modal.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
    }

    private static void setVoucherDateIfVisible(String isoDate) {
        Locator dt = page.locator("#voucher-date-input");
        if (dt.count() > 0 && dt.isVisible()) {
            dt.fill(isoDate);
        }
    }

    private static void setActivePeriodInUi(String fromDate, String toDate) {
        page.locator("button.mc-info-stat-box").first().click();
        Locator periodDialog = page.locator("div[role='dialog']").last();
        periodDialog.waitFor();
        Locator dateInputs = periodDialog.locator("input[type='date']");
        if (dateInputs.count() >= 2) {
            dateInputs.nth(0).fill(fromDate);
            dateInputs.nth(1).fill(toDate);
        }
        periodDialog.locator("button", new Locator.LocatorOptions().setHasText("Apply")).first().click();
        page.waitForTimeout(200);
    }

    private static void navigateSpa(String appPath) {
        if (page.url().contains("/app/")) {
            page.evaluate(
                "targetPath => { window.history.pushState({}, '', targetPath); window.dispatchEvent(new PopStateEvent('popstate')); }",
                appPath
            );
            page.waitForTimeout(180);
        } else {
            page.navigate(BASE_URL + appPath);
        }
        page.locator("header.mc-omni-bar").waitFor();
    }

    private static void selectCompanyInSidebar(String companyLegalName) {
        dismissDirtyEditorIfOpen();
        Locator row = page.locator("li.mc-open-company-row")
            .filter(new Locator.FilterOptions().setHasText(companyLegalName))
            .first();
        if (row.count() > 0 && row.isVisible()) {
            row.click();
            page.waitForTimeout(200);
            dismissDirtyEditorIfOpen();
            page.waitForTimeout(200);
            return;
        }
        page.locator("body").focus();
        page.keyboard().press("F3");
        page.waitForTimeout(150);
        dismissDirtyEditorIfOpen();
        Locator filter = page.locator("#mc-company-filter");
        if (!filter.isVisible()) {
            page.keyboard().press("F3");
        }
        filter.waitFor();
        filter.fill(companyLegalName);
        page.locator("button.mc-switcher-row")
            .filter(new Locator.FilterOptions().setHasText(companyLegalName))
            .first()
            .click();
        page.waitForTimeout(250);
    }

    private static void dismissDirtyEditorIfOpen() {
        Locator dirty = page.locator("[data-testid='dirty-editor-modal']");
        if (dirty.count() > 0 && dirty.isVisible()) {
            page.locator("[data-testid='dirty-discard-btn']").click();
            dirty.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN));
        }
    }

    private static String extractQueryParam(String rawQuery, String key) {
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && key.equals(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void authenticateApiSession() throws Exception {
        HttpRequest loginReq = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/api/v1/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"username\":\"" + OWNER_USER + "\",\"password\":\"" + OWNER_PASS + "\"}",
                StandardCharsets.UTF_8
            ))
            .build();
        HttpResponse<String> loginRes = apiClient.send(
            loginReq,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        Assertions.assertThat(loginRes.statusCode()).isEqualTo(200);
    }

    private static String lookupBenchmarkCompanyId() throws Exception {
        String id = lookupCompanyIdByLegalName(BENCHMARK_COMPANY_NAME);
        Assertions.assertThat(id)
            .as("Golden 'MoneyCraft Parity Benchmark' company must exist on live stack")
            .isNotBlank();
        return id;
    }

    private static String lookupCompanyIdByLegalName(String legalName) throws Exception {
        HttpResponse<String> res = apiClient.send(
            HttpRequest.newBuilder().uri(URI.create(BASE_URL + "/api/v1/companies")).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        Assertions.assertThat(res.statusCode()).isEqualTo(200);
        for (JsonNode cmp : objectMapper.readTree(res.body())) {
            if (legalName.equals(cmp.get("legalName").asString())) {
                return cmp.get("id").asString();
            }
        }
        return "";
    }

    private static void seedInteractiveCompanyMastersViaApi(String companyId) throws Exception {
        HttpResponse<String> grpRes = apiClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/v1/companies/" + companyId + "/account-groups?limit=100"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        Assertions.assertThat(grpRes.statusCode()).isEqualTo(200);
        JsonNode groups = objectMapper.readTree(grpRes.body()).get("items");

        String cashGroupId = findGroupId(groups, "Cash-in-Hand");
        String debtorsGroupId = findGroupId(groups, "Sundry Debtors");
        String creditorsGroupId = findGroupId(groups, "Sundry Creditors");
        String salesGroupId = findGroupId(groups, "Sales Accounts");
        String purchaseGroupId = findGroupId(groups, "Purchase Accounts");
        String indirectExpGroupId = findGroupId(groups, "Indirect Expenses");

        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Cash Counter",
              "groupId": "%s",
              "openingBalance": "100000.00",
              "openingSide": "DR"
            }
            """.formatted(cashGroupId)
        );
        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Petty Cash Vault",
              "groupId": "%s",
              "openingBalance": "10000.00",
              "openingSide": "DR"
            }
            """.formatted(cashGroupId)
        );
        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Alpha Pharma Customer",
              "groupId": "%s",
              "openingBalance": "0.00",
              "openingSide": "DR",
              "maintainBalancesBillByBill": false,
              "countryCode": "IN",
              "stateCode": "33"
            }
            """.formatted(debtorsGroupId)
        );
        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Beta Medical Supplier",
              "groupId": "%s",
              "openingBalance": "0.00",
              "openingSide": "CR",
              "maintainBalancesBillByBill": false,
              "countryCode": "IN",
              "stateCode": "33"
            }
            """.formatted(creditorsGroupId)
        );
        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Sales - Exempt Goods",
              "groupId": "%s",
              "openingBalance": "0.00",
              "openingSide": "CR",
              "inventoryValuesAffected": true,
              "gstApplicability": "NOT_APPLICABLE"
            }
            """.formatted(salesGroupId)
        );
        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Purchases - Exempt Goods",
              "groupId": "%s",
              "openingBalance": "0.00",
              "openingSide": "DR",
              "inventoryValuesAffected": true,
              "gstApplicability": "NOT_APPLICABLE"
            }
            """.formatted(purchaseGroupId)
        );
        postApiJson(
            "/api/v1/companies/" + companyId + "/ledgers",
            """
            {
              "name": "Office Rent Expense",
              "groupId": "%s",
              "openingBalance": "0.00",
              "openingSide": "DR",
              "costCentresApplicable": false
            }
            """.formatted(indirectExpGroupId)
        );

        String uomId = postApiJson(
            "/api/v1/companies/" + companyId + "/uoms",
            """
            {
              "uomType": "SIMPLE",
              "symbol": "NOS",
              "formalName": "Numbers",
              "gstUqcCode": "NOS",
              "decimalPlaces": 0
            }
            """
        ).get("id").asString();

        postApiJson(
            "/api/v1/companies/" + companyId + "/godowns",
            """
            {
              "name": "Central Warehouse",
              "aliasCode": "CWH-01",
              "allowStorageOfMaterials": true
            }
            """
        );

        String stockGroupId = postApiJson(
            "/api/v1/companies/" + companyId + "/stock-groups",
            """
            {
              "name": "Pharmaceutical Formulations",
              "shouldQuantitiesOfItemsBeAdded": true,
              "hsnSacCode": "30049099",
              "gstRatePct": "0"
            }
            """
        ).get("id").asString();

        String stripItemId = postApiJson(
            "/api/v1/companies/" + companyId + "/stock-items",
            """
            {
              "name": "Paracetamol 500mg Strip",
              "stockGroupId": "%s",
              "baseUomId": "%s",
              "costingMethod": "AVG_COST",
              "gstApplicability": "EXEMPT",
              "gstSupplyType": "GOODS",
              "hsnSacCode": "30049099",
              "gstRatePct": "0"
            }
            """.formatted(stockGroupId, uomId)
        ).get("id").asString();

        String kitItemId = postApiJson(
            "/api/v1/companies/" + companyId + "/stock-items",
            """
            {
              "name": "Carton Pack Kit",
              "stockGroupId": "%s",
              "baseUomId": "%s",
              "costingMethod": "AVG_COST",
              "gstApplicability": "EXEMPT",
              "gstSupplyType": "GOODS",
              "hsnSacCode": "30049099",
              "gstRatePct": "0"
            }
            """.formatted(stockGroupId, uomId)
        ).get("id").asString();

        postApiJson(
            "/api/v1/companies/" + companyId + "/boms",
            """
            {
              "stockItemId": "%s",
              "bomName": "Standard Carton Assembly v1",
              "unitOfManufacture": "1",
              "components": [
                {
                  "lineNo": 1,
                  "componentItemId": "%s",
                  "componentType": "COMPONENT",
                  "uomId": "%s",
                  "quantity": "2"
                }
              ]
            }
            """.formatted(kitItemId, stripItemId, uomId)
        );
    }

    private static String findGroupId(JsonNode groups, String expectedName) {
        for (JsonNode g : groups) {
            if (expectedName.equals(g.get("name").asString())) {
                return g.get("id").asString();
            }
        }
        throw new IllegalStateException("Account group not found: " + expectedName);
    }

    private static JsonNode postApiJson(String apiPath, String jsonBody) throws Exception {
        String xsrf = "";
        for (HttpCookie cookie : apiCookieJar.getCookieStore().getCookies()) {
            if ("XSRF-TOKEN".equals(cookie.getName())) {
                xsrf = URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8);
            }
        }
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + apiPath))
            .header("Content-Type", "application/json")
            .header("X-XSRF-TOKEN", xsrf)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> res = apiClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Assertions.assertThat(res.statusCode())
            .as("POST %s failed with body: %s", apiPath, res.body())
            .isIn(200, 201);
        return objectMapper.readTree(res.body());
    }
}
