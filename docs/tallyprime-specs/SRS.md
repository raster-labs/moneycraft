# Software Requirements Specification (`SRS.md`) — TallyPrime Gold Release 7.2 (`v1.1.7.2`) & MoneyCraft Parity Specification

- **Document ID**: `MC-TALLYPRIME-GOLD-SRS-V2.2`
- **Target Baseline**: Installed Windows **TallyPrime Gold Release 7.2 (`v1.1.7.2`, Build `29802`)** inspected via `craft-anything-windows` (`fnd_10`: `ev_91e9105873d9bc273279bd30d3fbcbbcad958a726bb221dad17c595ddd9a3774`, `ev_36869dbd0e9868d761d5332bb37055f6ba8db38f9f7629ab7508fd48ea87814d`, `ev_8ab28d93da36752aa179108f8d8f720704ac83a790dc863ec2a9a542e13c9735`, `ev_c6828d686cf47bc49df27ee62c0faec5d34bc78463069e7c0fa00dff1cabe50e`, `ev_f7a937d2b843a79b460968c57ea974acce8e7c444fcda31f45c06ece2ef71f49`, plus `ev_f30da517c3e069e2c23dd4e589d4b1cdaccb4b73e9b90cd40a4491cc49e71aa3`, `ev_960be990b38db33f04233eb35b17f1647eaf118b7f04925a7bc14e5849a0d8a1`, `ev_99e6c5bbca31b776a2efccfe723270008f0beb8e380e7126a9a3958637c922fd`, `ev_4ed25bc08989daa711353bd15fef9ec20ee18a35c52ae0a67013f6a2ccf1b459`, `ev_734c89ddf139bb91ccf1e978face7978d2ed6bb61bc0646298f13192bc6aaf27`) and verified against **127** SHA-256 pinned Windows Lab Oracles (`argon_workspace/windows_lab_oracles/`).
- **Clean-Room & Governance Invariants (`GEN-001`–`GEN-006`, `SEC-015`)**:
  - MoneyCraft delivers **100% of TallyPrime Gold multi-user, data-exchange, accounting, inventory, and statutory capabilities** out of the box with **zero** software-key activation, **zero** seat/user/company/voucher caps, and **100% offline Private-LAN autonomy** (`GEN-003`).

---

## 1. Functional Requirements by Module

### 1.1 Gateway of Tally, Top Omni-Bar & Global Navigation (`FR-NAV-001`–`FR-NAV-006`)
1. **`FR-NAV-001` — Zero-Company Startup & Multi-Company Context**:
   - When `0` companies are open (`activeCompany == null`), the workspace displays the **`Select Company`** screen (`Create Company`, `Select Remote Company`, `Specify Path`, `Select from Drive`, and the list of companies with Number, Name, and Financial Year).
   - Creating a new company automatically opens the **`F11: Company Features`** modal (`Company created successfully`) immediately upon creation.
   - Multiple companies can be loaded simultaneously; the Gateway left pane displays `CURRENT PERIOD (Alt+F2)`, `CURRENT DATE (F2)`, and `List of Selected Companies` (with active company bolded and `Date of Last Entry`).
2. **`FR-NAV-002` — Top Omni-Bar Dropdown Menus (`Alt+K` through `F1`)**:
   - **`Alt+K` (`_K_: Company`)**: `Create`, `Alter`, `Change [F3]`, `Select [Alt+F3]`, `Shut [Ctrl+F3]`, `F11: Features`, `Security`, `TallyVault`, `Online Access`. (`Alter`, `Change`, `Shut`, `F11`, `Security` are disabled when `0` companies are open).
   - **`Alt+Y` (`_Y_: Data`)**: `Backup`, `Restore`, `Split` (`Verify Data`, `Split Data`), `Data Path`.
   - **`Alt+Z` (`_Z_: Exchange`)**: `Client/Server Sync`, `Send for e-Invoicing`, `Send for e-Way Bill`, `Download GST Returns / IMS`.
   - **`Alt+G` (`_G_: Go To`)**: Non-destructive modal stack (depth up to 8) preserving dirty voucher/master drafts underneath; `Esc` pops the top overlay in `< 16 ms` and restores exact cell focus.
   - **`Ctrl+G` (`=G=: Switch To`)**: Direct screen replacement guarded by `editor.dirty` confirmation.
   - **`Alt+F` / `Ctrl+F` (`Find` / `SmartFind`)**: Global search across Masters, Vouchers, Narrations, Bill References, GSTINs, and Part Numbers.
   - **`Alt+O` (`_O_: Import`)**: `Masters`, `Transactions`, `Bank Details / Statements`, `Manage -> Mapping Templates`, `Manage -> Sample Excel Files [Alt+L]`, `Configuration`, `Exceptions`.
   - **`Alt+E` (`_E_: Export`)**: `Current [Ctrl+E]`, `Masters`, `Transactions`, `GST Returns` (`GSTR-1`, `GSTR-3B`, `GSTR-9`), `TDS Returns (FVU 1.2)`, `Configuration`.
   - **`Alt+M` (`_M_: E-Mail / Share`)**: `Current [Ctrl+M]`, `Other`, `Configuration`.
   - **`Alt+P` (`_P_: Print`)**: `Current [Ctrl+P]`, `Other`, `Configuration [F12]`.
   - **`F1` / `Ctrl+F1` (`Help`)**: Non-modal air-gapped contextual help panel (`Layer 1` `Escape`-first dismissal).
3. **`FR-NAV-003` — Gateway of Tally 3-Level Hierarchy**:
   - **MASTERS**: `Create (C)`, `Alter (A)`, `Chart of Accounts (H)`.
   - **TRANSACTIONS**: `Vouchers (V)`, `Day Book (K)`.
   - **UTILITIES**: `Banking (N)`.
   - **REPORTS**: `Balance Sheet (B)`, `Profit & Loss A/c (P)`, `Stock Summary (S)`, `Ratio Analysis (R)`, `Display More Reports (D)` (`Trial Balance`, `Day Book`, `Cash Flow`, `Funds Flow`, `Account Books`, `Statements of Accounts`, `Inventory Books`, `Statements of Inventory`, `Statutory Reports`, `Exception Reports`, `Analysis & Verification`), `Dashboard (O)`.
4. **`FR-NAV-004` — In-Place `F11: Company Features` Progressive Modal**:
   - Accessible from any screen via `F11`, supporting **`Show more features`** and **`Show all features`** progressive toggles across all **28** company feature flags (`Accounting`, `Inventory`, `Taxation`, `Payroll`, `Others`, `Audit`).
   - Enforces dependent feature constraints (`Maintain Expiry Date for Batches` requires `Enable Batches`; `Enable Payroll Statutory` requires `Maintain Payroll`; `Integrate Accounts with Inventory` requires `Maintain Accounts && Maintain Inventory`; `Enforce MCA Edit Log` is non-disablable once enabled).
5. **`FR-NAV-005` — Context-Sensitive `F12: Configuration` Modal**:
   - Adapts to the active surface (`Voucher Entry`, `Master Creation/Alteration`, `Financial Statements & Reports`, `Print / Export`) and persists per company/workstation.
6. **`FR-NAV-006` — `Show More` / `Show Inactive` & One-Click Feature Activation**:
   - Inside `Create` / `Alter` / `Chart of Accounts` and `F10: Other Vouchers`, toggling **`Show More`** and **`Show Inactive`** reveals conditional masters and voucher types with `(Inactive)` badges; selecting an inactive item prompts to enable the required `F11` feature in-place.

---

### 1.2 Accounting, Inventory, Payroll & Statutory Masters (`FR-MST-001`–`FR-MST-005`)
1. **`FR-MST-001` — Predefined Chart of Accounts (28 Account Groups & 2 System Ledgers)**:
   - Every company is seeded with the **28 predefined Account Groups** (`15` Primary groups + `13` sub-groups) and **2 predefined System Ledgers** (`Cash` under `Cash-in-Hand`, and `Profit & Loss A/c` primary ledger with `group_id = NULL`).
   - Group fields: `Name`, `Alias` (`F12`), `Under` (`Primary` or parent group), `Nature of Group` (`ASSETS`, `LIABILITIES`, `INCOME`, `EXPENSES`), `Does it affect Gross Profit?`, `Group behaves like a sub-ledger`, `Nett Debit/Credit Balances for Reporting`, `Used for Calculation`, `Method to Allocate when used in Purchase Invoice` (`NOT_APPLICABLE`, `APPROPRIATE_BY_QTY`, `APPROPRIATE_BY_VALUE`).
2. **`FR-MST-002` — Ledger Master & Sub-Ledger Constraints**:
   - Fields: `Name`, `Alias`, `Under` (mandatory Account Group), `Maintain balances bill-by-bill` (`F11.billWiseEnabled`), `Default credit period (Days)`, `Check for credit days during voucher entry`, `Specify credit limit`, `Inventory values are affected`, `Cost centres are applicable`, `Activate interest calculation`, `Currency of ledger`, `Type of Ledger` (`Not Applicable`, `Invoice Rounding`, `Discount`), `Type of Duty/Tax` (`GST`, `TDS`, `TCS`, `Others`/`NONE`), `Tax Head` (`CGST`, `SGST/UTGST`, `IGST`, `Cess`), `Percentage of Calculation`, `Rounding Method` (`Not Applicable`, `Downward`, `Normal`, `Upward`), `Include in Assessable Value calculation for` (`GST` / `Not Applicable`), `Method of Calculation` (`Based on Value`, `Based on Quantity`), Mailing Details (`Name`, `Address`, `State`, `Country`, `Pincode`), Banking Details (`Provide bank details`, `A/c No.`, `IFSC Code`, `Bank Name`), Tax Registration Details (`PAN/IT No.`, `Registration Type`: `Regular`/`Composition`/`Consumer`/`Unregistered`/`SEZ`, `GSTIN/UIN` validated with Mod-36 check digit), `Opening Balance` + `Dr`/`Cr`.
3. **`FR-MST-003` — 24 Predefined Voucher Types & Numbering Methods**:
   - Seeded with all **24 predefined Voucher Types** (`Contra`, `Payment`, `Receipt`, `Journal`, `Sales`, `Purchase`, `Debit Note`, `Credit Note`, `Memorandum`, `Reversing Journal`, `Stock Journal`, `Physical Stock`, `Delivery Note`, `Receipt Note`, `Rejections In`, `Rejections Out`, `Sales Order`, `Purchase Order`, `Job Work Out Order`, `Job Work In Order`, `Material In`, `Material Out`, `Attendance`, `Payroll`).
   - Voucher Numbering Methods: `Automatic`, `Automatic (Manual Override)`, `Manual`, `Multi-user Auto`, `None`.
   - Numbering Behavior on Insertion/Deletion: `Retain Original Voucher No.` (`AUTOMATIC_RETAIN_NUMBERS` with `Ctrl+Alt+U` unused voucher number pool) vs `Renumber Vouchers`.
4. **`FR-MST-004` — Inventory Masters (`Stock Group`, `Stock Category`, `Stock Item`, `UOM`, `Godown`, `Price Levels/Lists`)**:
   - **Units (`UOM`)**: `Simple` (`Symbol`, `Formal Name`, `UQC`, `Number of decimal places` `0..6`) and `Compound` (`First Unit`, `Conversion factor`, `Second Unit`).
   - **Godowns / Locations**: Hierarchical tree seeded with `Main Location` (`Primary`), supporting `Our Stock with Third Party` and `Third Party Stock with Us`.
   - **Stock Groups & Stock Categories**: Hierarchical trees with `Should quantities of items be added` and 6-tier GST rate inheritance.
   - **Stock Items**: `Name`, `Alias`, `Part No.`, `Under` (`Stock Group`), `Category` (`Stock Category`), `Units` (`Base UOM`), `Alternate Units` + conversion ratio, `Maintain in batches` (`Track date of manufacturing`, `Use expiry dates`), `Set components (BOM)`, `Costing Method` (`Avg. Cost`, `FIFO`, `FIFO Perpetual`, `Last Purchase Cost`, `Std. Cost`, `At Zero Cost`), `Market Valuation Method` (`Avg. Price`, `Last Sale Price`, `Std. Price`, `At Zero Price`), `GST Applicability`, `HSN/SAC` (`4`/`6`/`8` digits), `Taxability Type` (`Taxable`, `Exempt`, `Nil Rated`, `Non-GST`), `IGST/CGST/SGST/Cess %`, `Type of Supply` (`Goods` / `Services`), `Is Tobacco / Pan Masala Deemed MRP Item` (`Rel 7.2`), `Standard Rates`, `Opening Balance` (`Quantity`, `Rate`, `per`, `Value`, plus Godown/Batch breakup).
5. **`FR-MST-005` — Cost Centres, Currencies, Budgets, Scenarios & Statutory Masters**:
   - **Cost Categories & Cost Centres**: `Allocate Revenue Items`, `Allocate Non-Revenue Items`, hierarchical Cost Centres.
   - **Currencies & Rates of Exchange**: ISO code, symbol, decimal places (`0..6`), standard/selling/buying exchange rates by date.
   - **Statutory Masters**: `Multi-GSTIN Registrations`, `TDS Nature of Payment` (`Section`, `Payment Code`, `Single Bill Threshold`, `Annual Threshold`, `Rate with PAN`, `Rate without PAN` `20%`), `TCS Nature of Goods`.

---

### 1.3 Voucher & Data Entry Engine (`FR-VCH-001`–`FR-VCH-005`)
1. **`FR-VCH-001` — Entry Modes (`Ctrl+H`) & Accounting Invariants**:
   - Supports `ITEM_INVOICE`, `ACCOUNTING_INVOICE`, and `AS_VOUCHER` (plus `Single Entry` vs `Double Entry` for `Contra`/`Payment`/`Receipt` and `Use for Manufacturing Journal` for `Stock Journal`).
   - Every `POSTED` (`NORMAL`) voucher must satisfy `COUNT(mc_entry) >= 2`, `SUM(DR) == SUM(CR) > 0`, and `mc_period.state == 'OPEN'` enforced at database commit via `DEFERRABLE INITIALLY DEFERRED` constraint trigger `trg_mc_journal_double_entry_balance`.
2. **`FR-VCH-002` — Non-Posting & Cancelled Voucher States**:
   - `OPTIONAL` (`Ctrl+L`), `MEMORANDUM`, `REVERSING_JOURNAL`, and `DRAFT` persist in `mc_journal` + `mc_journal_line` with **`0` rows in `mc_entry`** (`₹ 0.00` impact on Trial Balance, P&L, Balance Sheet, Stock Summary, and GST returns).
   - `CANCEL` (`Alt+X`) zeroes all ledger/inventory/bill/cost postings while retaining `voucher_number` for `GSTR-1` Table 13 Document Summary and recording an immutable `CANCEL` snapshot in `mc_edit_log_version`.
3. **`FR-VCH-003` — Sub-Allocations (`Bill-Wise`, `Godown/Batch`, `Cost Centre`, `Bank Allocations`)**:
   - **Bill-Wise (`mc_bill_allocation`)**: `New Ref`, `Agst Ref` (validated against open bill balance), `Advance`, `On Account`; `SUM(signed bill allocations) == parent party line amount`.
   - **Godown / Batch (`mc_batch_allocation`)**: Validates batch expiry dates and tracks lot-level inward/outward quantities.
   - **Cost Centre (`mc_cost_allocation`)**: `100%` allocation per active Cost Category on applicable ledgers.
   - **Bank Allocations (`mc_bank_allocation`)**: `Cheque`, `e-Fund Transfer (NEFT/RTGS/IMPS/UPI)`, `Card`, `Cash`, `Instrument No.`, `Instrument Date`, `Bank Date` (for BRS).
4. **`FR-VCH-004` — Dynamic `F11` / `F12` Grid Reactivity**:
   - `VoucherGridComponent` dynamically adapts columns (`Actual` & `Billed` Qty vs single `Quantity`; `Disc %` column; `Price Level` header selector; `Dr`/`Cr` vs `By`/`To`; per-line `Line Narration`; `Supplier Invoice No. & Date`; `Cur Bal:` indicator) to active `F11` and `F12` flags.
5. **`FR-VCH-005` — Inline Creation (`Alt+C`), Calculator (`=` / `Alt+C`), Stock Query (`Alt+S`), Unused Nos (`Ctrl+Alt+U`) & Stat Adjustment (`Alt+J`)**:
   - `Alt+C` inside any master selector opens inline master creation and returns the created master directly to the active cell.
   - `Ctrl+Alt+U` picks from unused/deleted voucher numbers when numbering behavior is `Retain Original Voucher No.`.
   - `Alt+S` pushes `Stock Query (360° Item View)` onto the `Alt+G` stack.

---

### 1.4 Data Import & Export Modules (`Alt+O` & `Alt+E` / `Ctrl+E`) (`FR-EXG-001`–`FR-EXG-006`)
Based directly on `craft-anything-windows` static analysis of `tally.exe` (`ev_99e6c5bbca31b776a2efccfe723270008f0beb8e380e7126a9a3958637c922fd`, `ev_4ed25bc08989daa711353bd15fef9ec20ee18a35c52ae0a67013f6a2ccf1b459`) and live Windows Lab Oracles:

1. **`FR-EXG-001` — Master Import Duplicate Handling (`Behaviour of import if master already exists` — `DUPMASTER`)**:
   - Every Master import (`XML`, `JSONEx`, `Excel`) must support the three canonical TallyPrime duplicate modes:
     1. **`COMBINE_OPENING_BALANCES` (`Combine Opening Balances`)**:
        - For an existing **Ledger**, nets the existing signed opening balance (`-Dr` / `+Cr`) with the imported signed opening balance (`net = existingSigned + importedSigned`), updating `opening_balance = abs(net)` and `opening_side = (net <= 0 ? 'DR' : 'CR')`, and increments `alter_id` / `mc_edit_log_version` (`ALTER`).
        - For an existing **Stock Item**, adds the imported `openingQty` and `openingValue` to the existing item's opening quantity and value, recomputing weighted `openingRate = (newOpeningQty > 0 ? newOpeningValue / newOpeningQty : 0)`, and increments `alter_id` / `mc_edit_log_version` (`ALTER`).
     2. **`IGNORE_DUPLICATES` (`Ignore Duplicates`)**:
        - Skips any Group, UOM, Godown, Stock Group, Stock Item, or Ledger that already exists in the company (matched case-insensitively by name), leaving existing balances and attributes untouched.
     3. **`MODIFY_WITH_NEW_DATA` (`Modify with New Data`)**:
        - Replaces the existing Ledger or Stock Item attributes (`openingBalance`, `openingSide`, `groupName`, `maintainBalancesBillByBill`, `gstin`, `stateName`, `taxType`, `dutyHead`, `openingQty`, `openingRate`, `openingValue`, `costingMethod`) with the imported values and records an `ALTER` snapshot in `mc_edit_log_version`.
2. **`FR-EXG-002` — Transaction Import & `Overwrite Vouchers with Same GUID`**:
   - When importing Transactions (`XML`, `JSONEx`, `Excel`), `Alt+O > Configuration > Overwrite vouchers with same GUID`:
     - When `overwriteVoucherByGuid = false` (default): Vouchers whose `GUID` (`source_guid`) already exists in `mc_journal` are skipped (`vouchersSkipped++`).
     - When `overwriteVoucherByGuid = true`: Existing vouchers matching `source_guid` are updated in-place via `voucherService` (reversing prior `mc_entry` postings and posting the new voucher lines while incrementing `alter_id` and recording an `ALTER` version in `mc_edit_log_version`).
3. **`FR-EXG-003` — Import Exception Handling & `Resolve Exceptions` Workflow (`Alt+O > Configuration`)**:
   - Supports TallyPrime's three exception behaviors (`ev_4ed25bc08989daa711353bd15fef9ec20ee18a35c52ae0a67013f6a2ccf1b459`):
     1. **`STOP_AT_FIRST_EXCEPTION` (`DiscontinueImport` — *"Stop Import at First Exception"*)**: Aborts the import immediately upon encountering any invalid/unbalanced record or missing required master (`422`).
     2. **`RECORD_EXCEPTIONS_AND_IMPORT` (`ContinueImport` — *"Record Exceptions and Import"*)**: Imports all valid masters/vouchers and records any invalid/unresolved items (e.g., voucher with unbalanced lines, missing date, or invalid GSTIN) into the Import Job's **Exception Queue** (`exceptions` list in `diagnostics_json` / `ImportExceptionItemDto`) so operators can inspect and resolve them (*"Created due to Data Exception Resolution"*).
     3. **`IGNORE_EXCEPTIONS_AND_IMPORT` (`IgnoreAllExceptions` — *"Ignore Exceptions and Import"*)**: Imports all valid records and auto-creates missing referenced masters under default fallback groups while skipping unparseable rows without halting.
4. **`FR-EXG-004` — Manage Mapping Templates (`Alt+O > Manage > Mapping Templates`)**:
   - **Template Scope**: `THIS_COMPANY` (scoped to `company_id`) vs `ALL_COMPANIES` (shared across all companies on the installation, `ev_99e6c5bbca31b776a2efccfe723270008f0beb8e380e7126a9a3958637c922fd`).
   - **Column & Rule Validation**:
     - Validates Excel column letters (`A`..`ZZ`) and `fromColumn <= toColumn` when column ranges are specified (`422 MC-EXG-TEMPLATE-COLUMN-RANGE`).
     - Requires at least one field mapping (`422 MC-EXG-TEMPLATE-MAPPING-EMPTY`).
     - Supports **Fixed Field Values** (`defaultValues`, e.g., `groupName = "Sundry Debtors"` for all rows), **Nett Opening Balance** computation from separate `Debit` and `Credit` columns, and **Row Exclusion Conditions** (`excludeRowColumn`, `excludeRowOperator`: `CONTAINS` | `EQUALS` | `STARTS_WITH`, `excludeRowValue`, e.g., exclude rows where `Day` contains `Sunday`).
     - Supports deleting individual templates or all templates for `THIS_COMPANY` / `ALL_COMPANIES`.
5. **`FR-EXG-005` — Sample Excel Files Generator (`Alt+O > Manage > Sample Excel Files [Alt+L]`)**:
   - Generates downloadable OpenXML `.xlsx` sample templates with canonical headers and sample rows for:
     - `ACCOUNTING_MASTERS` (`Ledgers` & `Groups`)
     - `INVENTORY_MASTERS` (`StockItems`, `StockGroups`, `UOMs`, `Godowns`)
     - `ACCOUNTING_VOUCHERS` (`Vouchers` & `VoucherLines`)
     - `ITEM_VOUCHERS` (`Vouchers`, `VoucherLines`, & `InventoryLines`)
     - `ALL` (Complete multi-sheet sample workbook)
6. **`FR-EXG-006` — Comprehensive Export Engine (`Alt+E` & `Ctrl+E`) & All Supported Formats**:
   - **Export Scopes**: `ALL` (All Masters & Vouchers), `MASTERS` (Accounting & Inventory Masters, with `includeDependentMasters: true/false`), `VOUCHERS` (Transactions within `fromDate..toDate`, with `includeDependentMasters: true/false`, `includeNarration: true/false`, `includeBillWise: true/false`, `includeInventory: true/false`), `TRIAL_BALANCE` (Closing balances), `REMINDER_LETTERS` (Multi-Account overdue party bills reminder report), `CONFIRMATION_OF_ACCOUNTS` (Multi-Account party ledger confirmation statement).
   - **Supported Export Formats (`ev_4ed25bc08989daa711353bd15fef9ec20ee18a35c52ae0a67013f6a2ccf1b459`)**:
     1. **`XML`** (`XML (Data Interchange)` — canonical `<ENVELOPE>` with universal sign law `-Dr` / `+Cr`, pure-ASCII header comment, and `&#4;` control char stripping).
     2. **`JSONEX`** (`JSON / JSONEx (Data Exchange)` — `header: { version: 1, tallyrequest: "Export", type: "Data", id: "MoneyCraft JSONEx" }`, exact string decimals).
     3. **`EXCEL`** (`Excel (Spreadsheet) .xlsx` — Apache POI OpenXML workbook).
     4. **`ASCII`** (`ASCII (Comma Delimited) .csv` — RFC 4180 + OWASP formula-injection safe comma-delimited text export).
     5. **`HTML`** (`HTML (Web-Publishing) .html` — self-contained UTF-8 semantic HTML table document with tabular numerals and optional digital signature block).
     6. **`PDF`** (`PDF (Read-only document)` — rendered via deterministic PDF service with embedded `Manrope` / `JetBrains Mono` fonts and optional digital signature block metadata).
     7. **`JPEG`** (`JPEG (Image)` — visual snapshot export in `ReportOutputModalComponent`).

---

### 1.5 Multi-User / Gold Edition Specifics (`FR-GOLD-001`–`FR-GOLD-004`)
Based on `craft-anything-windows` inspection of `tally.exe` (`ev_734c89ddf139bb91ccf1e978face7978d2ed6bb61bc0646298f13192bc6aaf27`) and `tally-prime__security-management-in-tallyprime.md`:

1. **`FR-GOLD-001` — Multi-User Concurrency & `Multi-user Auto` Voucher Numbering**:
   - Unlimited concurrent multi-user sessions against Dual-Cluster PostgreSQL 18 (`Authority DB` + `Business DB`) with deterministic lock acquisition order (`MCJ1-TXN-01`) and `ETag` / `If-Match` optimistic concurrency control.
   - `Multi-user Auto` voucher numbering allocates gap-free sequential numbers at transaction commit under row-level lock on `mc_number_series` without collision across concurrent operators.
2. **`FR-GOLD-002` — Granular Security Levels & Runtime Grant Enforcement (`ma_grant`)**:
   - Built-in Security Levels (`COMPANY_ADMIN` / `Owner`, `SENIOR_ACCOUNTANT`, `AUDITOR_APPROVER` / `Tally.NET Auditor`, `DATA_ENTRY_OPERATOR` / `Data Entry`, `INVENTORY_CLERK`, `READ_ONLY_VIEWER`) plus custom per-grant permission sets in `ma_grant`.
   - **Mandatory Runtime Enforcement of Per-Grant Restrictions (`ma_grant`)**:
     - **`backdated_days_limit`**: When a principal's active grant for `companyId` specifies `backdated_days_limit >= 0` (and the principal is not `INSTALLATION_OWNER` or `COMPANY_ADMIN`), creating or altering a voucher whose `voucher_date` is more than `backdated_days_limit` days before the company's current working date / max voucher date (or before company `books_beginning_from` / audit lock date) must be rejected with `403 MC-SEC-BACKDATED-LIMIT-EXCEEDED` (*"Security Violation! Voucher date exceeds allowed back-dated days limit"*).
     - **`voucher_type_allowlist`**: When a principal's active grant for `companyId` has a non-empty `voucher_type_allowlist`, creating or altering a voucher of a type not in `voucher_type_allowlist` must be rejected with `403 MC-SEC-VOUCHER-TYPE-FORBIDDEN`.
     - **`export_permitted`**: When a principal is not `INSTALLATION_OWNER`/`COMPANY_ADMIN` and none of their active grants on `companyId` has `export_permitted = true`, all data export endpoints (`/exports/xml`, `/exports/jsonex`, `/exports/excel`, `/exports/ascii`, `/exports/html`) must reject the request with `403 MC-SEC-EXPORT-FORBIDDEN` (*"You do not have access to Print/Export/Share"*).
3. **`FR-GOLD-003` — TallyAudit & Immutable MCA Edit Log (`mc_edit_log_version` + `MC-AUDIT-2`)**:
   - Every Master and Voucher creation, alteration, cancellation, migration, and data-exchange modification increments monotonic `alter_id` (`ALTERID = 1, 2, 3...`) and appends an immutable `mc_edit_log_version` snapshot with before/after field diffs (`Alt+Q` redline viewer).
   - Every administrative and accounting event appends to the SHA-256 hash-chained `mc_audit_event` ledger (`MC-AUDIT-2`).
4. **`FR-GOLD-004` — Client/Server Data Synchronization (`Alt+Z > Client/Server Sync`)**:
   - Supports configuring and executing **Client/Server Data Synchronization Rules** (`SyncRuleDto`, `SyncSessionResultDto`):
     - Rule attributes: `ruleName`, `ruleType` (`MASTER_SYNC` | `TRANSACTION_SYNC` | `FULL_SYNC`), `direction` (`BOTH_WAYS` | `SEND_ONLY` | `RECEIVE_ONLY`), `targetCompanyGuid`, `targetCompanyName`, `serverUrl`, `syncMasterDuplicateRule` (`COMBINE_OPENING_BALANCES` | `IGNORE_DUPLICATES` | `MODIFY_WITH_NEW_DATA`), `overwriteVoucherByGuid`, `active`.
     - **Self-Sync GUID Guard** (`ev_734c89ddf139bb91ccf1e978face7978d2ed6bb61bc0646298f13192bc6aaf27`): Rejects synchronization when Client and Server companies share the same company GUID (`422 MC-SYNC-SAME-COMPANY-GUID`: *"Client and Server companies have same GUID. Cannot proceed with synchronization."*).
     - Tracks `lastSyncAlterId` watermark (`ALTERID` incremental delta sync) and records any sync exceptions into the Sync Exceptions log (`Alt+Z > Sync Exceptions`).

---

### 1.6 Financial, Inventory & Statutory Reports (`FR-RPT-001`–`FR-RPT-004`)
1. **`FR-RPT-001` — Trial Balance, Profit & Loss A/c, Balance Sheet & Schedule III**:
   - **Trial Balance**: Hierarchical group/ledger rollup with `Opening Balance`, `Transactions (Dr/Cr)` / `Nett Transactions`, `Closing Balance`, and `% of Total` (`F12`), plus `Alt+N` Auto-Column comparative views. Invariant: `SUM(Closing Dr) == SUM(Closing Cr)`.
   - **Profit & Loss A/c**: Splits `Trading Account` (`Affects Gross Profit = Yes` + Opening/Closing Stock from integrated `Stock Summary`) to compute `Gross Profit / Loss`, and `Income & Indirect Expenses` to compute `Net Profit / Loss`.
   - **Balance Sheet & Schedule III**: Horizontal (`Liabilities` vs `Assets`) and Vertical (`Sources of Funds` vs `Application of Funds`) layouts, transferring `Net Profit / Loss` from P&L and `Closing Stock` from `Stock Summary`, plus `Unadjusted Forex Gain/Loss` from multi-currency bill settlements.
2. **`FR-RPT-002` — Stock Summary & High-Precision Valuation (`FIFO` vs `Avg. Cost`)**:
   - **`FIFO`**: Consumes oldest positive inward cost layers chronologically; Purchase Return `Debit Note` linked to an inward bill reduces that specific inward layer directly (`10 -> 8 @ 130.00`) before dynamically recomputing subsequent outward sales (`Golden Vector B: 11 NOS = ₹ 1,340.00`).
   - **`Avg. Cost`**: Maintains intermediate unit cost at `BigDecimal` `MathContext.DECIMAL128` (`scale >= 10`) and rounds `HALF_UP` to `currency_scale` (`2` decimals) **only** on final closing value (`11 * (2390 / 23) = ₹ 1,143.04`).
3. **`FR-RPT-003` — Ratio Analysis, Cash/Funds Flow & Bill-Wise Ageing**:
   - **Ratio Analysis**: Principal Groups (`Working Capital = Current Assets - Current Liabilities`, `Cash-in-Hand`, `Bank Accounts`, `Sundry Debtors`, `Sundry Creditors`, `Sales`, `Purchases`, `Stock-in-Hand`, `Net Profit`) and Principal Ratios (`Current Ratio = Current Assets / Current Liabilities`, `Quick Ratio = (Current Assets - Closing Stock) / Current Liabilities`, `Debt/Equity Ratio`, `Gross Profit %`, `Net Profit %`, `Operating Cost %`, `Receivable Turnover in Days`, `Return on Working Capital %`).
   - **Bills Receivable / Payable**: Open bill-wise balances bucketed into configurable ageing intervals (`0–30`, `31–60`, `61–90`, `>90` days) plus Multi-Account **Reminder Letters** and **Confirmation of Accounts** exports.
4. **`FR-RPT-004` — Statutory GST (`GSTR-1`, `GSTR-2B`, `IMS`, `GSTR-3B`, `GSTR-9`) & TDS (`Form 26Q`)**:
   - **`GSTR-1`**: Tables `4A/4B/4C/6B/6C (B2B)`, `5A/5B (B2CL)`, `6A (Exports)`, `7 (B2CS)`, `8 (Nil/Exempt/Non-GST)`, `9B (CDNR/CDNUR)`, `11A/11B (Advances)`, `12 (HSN Summary Phase III 4/6/8-digit)`, `13 (Document Summary including Cancelled voucher numbers)`, plus **Uncertain Transactions (`Ctrl+J`)**.
   - **`GSTR-2B` & `IMS` (Rel 7.2)**: `Accept`, `Reject`, `Pending`, `No Action` with **Partial ITC Reduction** (`itc_reduction_amount <= portal_itc_amount`), mandatory **Remarks** validation, and **Effective Filing Period** dynamic `GSTR-3B` Table 4 ITC recomputation.
   - **One-Click `GSTR-9` Annual Return**: Tables 4 through 19 direct `.xlsx` and portal `.json` export.
   - **`TDS Form 26Q`**: Protean (NSDL) FVU 1.2 compliant text export.

---

## 2. Non-Functional Requirements (`NFR-001`–`NFR-004`)
1. **`NFR-001` — Exact Fixed-Point Monetary Arithmetic (`MCJ1-DATA-01`)**:
   - PostgreSQL 18 `numeric` with `CHECK (val >= 0 AND val < 1000000000000000000 AND val = trunc(val, 6))`; Java 25 `BigDecimal` (`RoundingMode.UNNECESSARY` input validation); Angular 22 string-serialized decimals + `ExactDecimal` (`bigint`) math. Zero `float`/`double` anywhere.
2. **`NFR-002` — Dual-Cluster PostgreSQL 18 Trust Split & Tenant Isolation (`FND-DATA-070`)**:
   - Protected Authority DB (`ma_*`, `:55481`) holds credentials, WebAuthn passkeys, OIDC links, grants, wrapped Tink DEKs, and `SPRING_SESSION`.
   - Business DB (`mc_*`, `:55480`) holds accounting books guarded by `AuthorityGuard` advisory lock `pg_try_advisory_lock(1296253269, 1)`, PostgreSQL `RLS` (`app.current_company_id`), and composite `(company_id, id)` foreign keys.
3. **`NFR-003` — Mouseless Keyboard Latency & Brand `v0.2.0` Typography**:
   - `< 16 ms` `Alt+G` stack push/pop and 60 FPS `CdkVirtualScrollViewport` voucher grid navigation.
   - Bundled `Manrope 4.505` (`tabular-nums lining-nums`, explicit `font-weight`) for prose/amounts (`₹` `U+20B9`, `−` `U+2212`) and `JetBrains Mono 2.304` (`"zero" 1, "calt" 0, "liga" 0`) for IDs/codes/hashes.
4. **`NFR-004` — Dual-WAR Servlet Container Portability**:
   - Identical `moneycraft.war` deploys and passes integration tests on both **Apache Tomcat 11.0.x** and **WildFly 42.0.0.Final**.


---

## 9. Five-Loop Deep-Dive Inspection & Functional Requirements (`fnd_5`–`fnd_9`)

### 9.1 Loop 1 — Gateway, Top Menu Bar (`Alt+K/Y/Z/G/O/E/M/P`, `F11`/`F12`) & Master/Voucher Field Validations (`fnd_10`, `fnd_5`, `ev_91e91058...`, `ev_36869dbd...`, `ev_8ab28d93...`, `ev_f7a937d2...`)
1. **Gateway of Tally, Top Menu Bar & F11 Feature Predicates (`0x1410adcf8`, `0x14114b118`, `0x1411e9830`–`0x1411eaa20`)**:
   - `Company FeaturesOnCreate` (`0x14114b118`) triggers automatic in-place `F11: Company Features` presentation after company creation.
   - `F11` feature predicates (`$$IsBillWiseOn`, `$$IsAllBillWiseOn`, `$$IsCostCentresOn`, `$$IsStockCategoryOn`, `$$IsBatchWiseOn`, `$$IsMultiGodownOn`) dynamically gate Master Creation/Alteration and Voucher Entry fields, while `Show More` / `Show Inactive` in Gateway `Create` / `Alter` / `Chart of Accounts` reveals inactive masters with one-click `F11` activation prompts.
   - Accounting & Inventory Master definitions enforce `Type of Ledger` (`0x141402538`), `Rounding Method` (`0x141400e00`), `Is Group used for calculation` (`0x14148e1d8`), `Method of Voucher Numbering` (`0x141430858`), `Prevent Duplicates` (`0x141432e18`), `Costing Method` (`0x14140b5e8`), `Market Valuation Method` (`0x14140b668`), `Alternate Units` conversion factor checks (`0x14108ea30`), and Godown/Batch item allocation uniqueness (`DupBatchGodown` `0x14108f720`, `GodownStorageMatNotAllow` `0x141090cd0`).
2. **Credit Limit & Override Credit Limit (`0x141407c90`, `0x141405c40`)**:
   - Party ledgers under `Sundry Debtors` or `Sundry Creditors` may define a non-negative `creditLimit` (`numeric`, scale `currency_scale`).
   - During `Sales` or `Debit Note` voucher entry (`POST`), if the party's post-voucher closing debit balance exceeds `creditLimit` and `overrideCreditLimit` is not explicitly enabled, the voucher is rejected (`422 MC-VOUCHER-CREDIT-LIMIT-EXCEEDED`).
3. **Bill-Wise Date & Opening Break-Up Validations (`0x14108dd80`, `0x14108de40`, `0x14108dee0`)**:
   - In Bill-Wise allocations, `dueDate` (when specified) must be greater than or equal to `billDate` (`Due Date is earlier than Bill Date in Bill-wise details`).
   - For Opening Balance bill break-ups, `billDate` must not be later than `booksBeginningFrom` (`Bill Date in Bill-wise details is later than Books beginning from date`).
4. **Effective Date (`0x141420bf0`, `0x14108cbb0`)**:
   - Vouchers support an optional `effectiveDate` distinct from `voucherDate` for credit-period and interest-calculation start dates; defaults to `voucherDate` when omitted.

### 9.2 Loop 2 — Voucher Entry (`F4`–`F10`), Sub-Ledgers (`Bill-Wise`, `Cost Centre`, `Bank`, `Godown/Batch`), Credit Limits, Shortcuts & Multi-Account Export (`fnd_2`, `fnd_11`, `fnd_12`, `ev_a28b80a4...`, `ev_82503a79...`, `ev_7433e7b2...`, `ev_3217a8d9...`, `ev_119b1f06...`)
1. **Voucher Entry Modes, Bidirectional `Ctrl+H` Conversion, Contra Cash/Bank Guard, Autofill (`Ctrl+Alt+F`), Duplicate (`Alt+2`), Delete Draft (`Alt+D`) & Credit Limit Override (`0x141407c90`, `0x141405c40`, `0x14124adf0`)**:
   - **Contra (`F4`) Cash/Bank Invariant**: Both `Single Entry` and `Double Entry (As Voucher)` modes on `Contra` restrict ledger selection and posting exclusively to `Cash-in-Hand`, `Bank Accounts`, and `Bank OD/OCC` groups (`422 MC-ACC-CONTRA-CASH-BANK-ONLY`).
   - **Bidirectional `Ctrl+H` (`applyPersistedView`) Mode Conversion**: On `Contra (F4)`, `Payment (F5)`, and `Receipt (F6)`, `Ctrl+H` converts losslessly in both directions between `SINGLE_ENTRY` and `AS_VOUCHER` (preserving Cash/Bank header account, bank allocations, and opposing ledger lines).
   - **Party Credit Limit Enforcement on Create & Alter (`0x141407c90`, `0x141405c40`)**: Both `POST /vouchers` (`createVoucher`) and `PUT /vouchers/{id}` (`updateVoucher`, excluding the voucher's own prior `mc_entry` rows) enforce `creditLimit` on `SALES` and `DEBIT_NOTE` vouchers (`422 MC-VOUCHER-CREDIT-LIMIT-EXCEEDED`), and `VoucherGridComponent` displays the party's `Credit Limit` badge with client-side projected debit validation and `Override Credit Limit` toggle.
   - **Voucher `Autofill` (`Ctrl+Alt+F`), `Duplicate Voucher` (`Alt+2`, `0x14124adf0`) & `Delete Draft` (`Alt+D`)**: `Ctrl+Alt+F` (`voucher.autofill`, `0x14135e928`) automatically populates intra-state (`CGST` + `SGST`) or inter-state (`IGST`) GST tax lines on invoices from the computed taxable base; `Alt+2` (`register.duplicateVoucher`, `0x14124adf0`) switches an open voucher in `VoucherGridComponent` into Duplication Mode (clearing `editingVoucherId`, allocating a new voucher number, and retaining all lines); `Alt+D` (`register.deleteVoucher`) clears an unsaved draft or blocks deletion of a posted voucher when MCA Edit Log is enforced (directing the user to `Alt+X` Cancel).
2. **Sub-Ledger Popover Validations (`Bill-Wise`, `Cost Centre`, `Bank`, `Godown/Batch`)**:
   - **Bill-Wise (`BILLALLOCATIONS.LIST`, `0x1410f3988`, `0x14108de40`)**: Enforces `SUM(signed bill_allocation.amount) == line.amount`, blocks `AGST_REF` over-settlement beyond open bill pending balance, rejects duplicate `(allocationType, billName)` rows within the same bill-wise allocation popover, and rejects `dueDate < voucherDate` (`422 MC-ACC-BILL-DUE-DATE-BEFORE-VOUCHER-DATE`: *"Due Date is earlier than Bill Date in Bill-wise details."*).
   - **Cost Centre (`CostCategory`, `0x1410908d0`, `0x1410b5870`, `0x1410b56b0`)**: Enforces 100% allocation per active Cost Category with inline `Alt+C` Cost Centre creation, and rejects duplicate Cost Centre rows within the same ledger line allocation (`0x1410908d0`: *"Name of Cost Centre is duplicate in Cost Allocation."*, `422 MC-ACC-DUPLICATE-COST-CENTRE`).
   - **Bank Allocations (`BANKALLOCATIONS.LIST`, `0x1410f39b8`, `0x141433d60`)**: Captures `BankTransactionType` (`Cheque`, `NEFT`, `RTGS`, `IMPS`, `UPI`, `Card`, `Cash`, `ECS/NACH`), `InstrumentNumber` (`0x14140f3e0`), `InstrumentDate` (`0x141082a18`), and `FavouringName` (`0x1410829d8`) with 100% amount balance.
   - **Godown & Batch Allocations (`BATCHALLOCATIONS.LIST` `0x1410f3a28`, `DupBatchGodown` `0x14108f700`, `0x14108f600`)**: Rejects expired batches on outward movement, blocks duplicate `(Godown, Batch)` combinations within the same item allocation popover, and rejects `expiryDate < mfgDate`.
3. **Day Book (`Alt+2` Duplicate, `Alt+A` Append, `Alt+I` Insert, `Alt+X` Cancel, `Alt+R` Remove Line, `Alt+U` Restore Line, `Alt+Q` Edit Log)**:
   - Verified against `tally.exe` (`0x14124adf0` Duplicate `Alt+2`, `0x14124b040` Append `Alt+A`, `0x14124b260` Insert `Alt+I`, `ev_c48e9838e2f8c81d70232a76e8b1efedd69675d3a669f5ed0b2c57a1e169ceff`): Day Book sorts canonically by `Date -> Voucher Type -> Voucher No.`, excludes `CANCELLED` rows from running `ExactDecimal` footer totals, and supports `Alt+2`, `Alt+A`, `Alt+I`, `Alt+X` (with `If-Match` optimistic locking), `Alt+R`/`Alt+U`/`Ctrl+U`, `Ctrl+B` Basis of Values, `Alt+F1` Detailed mode, and `Alt+Q` Edit Log drill-down.
4. **Multi-Account Export (`Alt+E > Multi Account Reports`) & Bank Statement Validation (`ev_0c5d7bf9...`, `fnd_6`)**:
   - Endpoint: `POST /api/v1/companies/{companyId}/exports/multi-account-report`
   - Supports `reportType`:
     - `CONFIRMATION_OF_ACCOUNTS`: Generates per-party ledger confirmation statements with opening balance, period debit/credit totals, closing balance (`DR`/`CR`), and optional bill-wise outstanding schedule as of `[fromDate, toDate]`.
     - `REMINDER_LETTER`: Generates per-party overdue bill reminder letters bucketed into configurable ageing slabs (`0-30`, `31-60`, `61-90`, `>90` days) as of `toDate`.
   - Supports output in structured `JSON`, standalone `HTML`, or `ASCII` (`.csv`).
   - Validates bank account number / ledger compatibility during Bank Statement import (`0x1410c8360`, `0x1410bcc48`) and supports period-scoped statement reset.

### 9.3 Loop 3 — Data Import (`Alt+O`) & Export (`Alt+E` / `Ctrl+E`), Mapping Templates, Sample Excel (`Alt+L`), Duplicate Rules, Exception Resolution & TallyAudit Activity Queries (`fnd_3`, `fnd_7`, `ev_eabb24da...`, `ev_78baeb75...`, `ev_28896d7b...`, `ev_a6924250...`, `ev_28947175...`, `ev_13fbe6ce...`, `ev_a0454525...`)
1. **Mapping Templates (`Alt+O > Manage > Mapping Templates`, `0x141079748`, `0x1410a68f0`–`0x1410a9c40`)**:
   - Supports `Default Template` (`0x141079748`) and custom Mapping Templates saved for `THIS_COMPANY` (`This Company`, `0x1410a6af0`) or `ALL_COMPANIES` (`All Companies on this computer`, `0x1410a6a40`), with template creation, listing, and deletion (`DELETE /api/v1/companies/{companyId}/exchange/templates/{templateId}`).
   - Enforces non-empty field mappings (`0x1410a8b80`, `422 MC-EXG-TEMPLATE-MAPPING-EMPTY`), valid Excel column range letters `A`..`ZZ` (`0x1410a8d80`), and `fromColumn <= toColumn` (`0x1410a8e70`, `422 MC-EXG-TEMPLATE-COLUMN-RANGE`) in both `MigrationAndExchangeComponent` and `DataExchangeService`, bounding mapped sheet column scanning to `[fromColumn .. toColumn]` (`0x1410a9590`), evaluating row exclusion conditions (`excludeRowColumn`, `excludeRowOperator`, `excludeRowValue`), and computing Nett Opening Balances from separate Debit/Credit columns.
2. **Sample Excel Files (`Alt+O > Manage > Sample Excel Files [Alt+L]`, `0x14109b7e8` `sample_excel.xlsx`)**:
   - Exposes `GET /api/v1/companies/{companyId}/exchange/sample-excel?type={ALL|ACCOUNTING_MASTERS|INVENTORY_MASTERS|ACCOUNTING_VOUCHERS|ITEM_VOUCHERS}` and a Sample Excel type selector + download trigger in `MigrationAndExchangeComponent`.
3. **Master Duplicate Rules (`0x141079688`–`0x141079720`), Voucher GUID Overwrite & Exception Resolution (`0x141092ac8`–`0x141092bb8`, `0x14107af90`, `0x1410c3af0`)**:
   - Enforces `COMBINE_OPENING_BALANCES` (`0x1410796d0`), `IGNORE_DUPLICATES` (`0x141079720`), and `MODIFY_WITH_NEW_DATA` (`0x141079688`) across Ledgers and Stock Items, plus `overwriteVoucherByGuid` on Vouchers.
   - Enforces `STOP_AT_FIRST_EXCEPTION` (`DiscontinueImport`, `0x141092af0`), `RECORD_EXCEPTIONS_AND_IMPORT` (`ContinueImport`, `0x141092b50`), and `IGNORE_EXCEPTIONS_AND_IMPORT` (`IgnoreAllExceptions`, `0x141092bb8`), and provides an interactive **Import Exceptions & Data Exception Resolution** register in `MigrationAndExchangeComponent` (`GET /exchange/exceptions`, `POST /exchange/exceptions/{exceptionId}/resolve`, tagging resolved vouchers with `"Created due to Data Exception Resolution"` `0x14107af90`).
4. **Multi-Format Export (`Alt+E` / `Ctrl+E`, `0x141078f38`–`0x14107da90`)**:
   - Supports `XML` (`XML (Data Interchange)` `0x141079100`), `JSONEX` (`JSONEx (Data Exchange)` `0x141078f90`/`0x141078fe8`), `EXCEL` (`Excel (Spreadsheet)` `0x141079318`), `ASCII` (`ASCII (Comma Delimited)` `0x1410790c8`), `HTML` (`HTML (Web-Publishing)` `0x141079140` with optional `digitalSignatureSignerName`), and `PDF` (`PDF (Read-only document)` `0x14107da08`).
5. **Edit Log & TallyAudit Activity Summary (`0x14117b1b0`, `0x14117b550`, `0x141133e48`)**:
   - Endpoint: `GET /api/v1/companies/{companyId}/exchange/edit-log-summary`
   - Query parameters:
     - `entityType`: `ALL` (default), `VOUCHER`, `LEDGER`, `STOCK_ITEM`
     - `onlyAltered`: `true` (`Basis of Values: Altered Vouchers/Masters`, where `versionNo > 1` or `alterId > 1`) or `false` (`Include All`)
     - `username`: Optional principal username filter (`0x14117b1b0`)
   - Returns each matching entity's `entityType`, `entityId`, `displayName`, `voucherNumber`, `activityType` (`CREATED`, `ALTERED`, `CANCELLED`, `MIGRATED`), `alteredDueTo`, `versionCount`, `latestAlterId`, `lastModifiedBy`, and `lastModifiedAt`.

### 9.4 Loop 4 — Multi-User Gold Edition Security Levels, TallyAudit / MCA Edit Log Version Comparison, Client/Server Synchronization (`Alt+Z`) & Dashboard Tiles Export (`fnd_4`, `fnd_8`, `ev_28b41244...`, `ev_f3162d93...`, `ev_39b4f41c...`, `ev_c87fbd29...`, `ev_0a38f2d8...`, `ev_1543c9d6...`, `ev_696b10b9...`)
1. **Multi-User Gold Edition Security Levels & Per-Grant Restrictions (`0x14107ae28` `Owner`, `0x1410b9a88` `Data Entry`, `0x1410bb6b8` `Tally.NET User`, `0x1410bb408` `Tally.NET Auditor`, `0x14140f090` `Security Level Name`, `0x1410b4b08` `Back Dated Vouchers`, `0x14147ae58` `PrintBefSaveDisallowList`)**:
   - `AdminConsoleComponent` (`Alt+K > Security`) provides canonical TallyPrime Gold Security Level presets (`OWNER`, `DATA_ENTRY`, `TALLY_NET_USER`, `TALLY_NET_AUDITOR`, `CUSTOM`) and granular per-grant inputs for `backdatedDaysLimit`, `voucherTypeAllowlist` (comma-separated allowed voucher types), and `exportPermitted` (`Allow Print / Export / Share`), persisting them via `PUT /api/v1/admin/principals/{principalRef}/grants`.
   - `RbacAuthorizationService`, `VoucherService`, and `DataExchangeService` enforce `backdatedDaysLimit` (`403 MC-SEC-BACKDATED-LIMIT-EXCEEDED`), `voucherTypeAllowlist` (`403 MC-SEC-VOUCHER-TYPE-FORBIDDEN`), and `exportPermitted` (`403 MC-SEC-EXPORT-FORBIDDEN`) on voucher creation (`POST`), voucher alteration (`PUT`), and all export endpoints.
2. **TallyAudit & MCA Edit Log Arbitrary Version Comparison (`Alt+Q`, `0x1410a1d78` `TallyPrime Edit Log`, `0x14142cf28` `IsTallyAuditOn`, `0x14142dcf8` `IsEditLogOn`, `0x1414522c8` `AlterID`, `0x141133d80` `EditLog User Name`)**:
   - `EditLogRedlineModalComponent` defaults to `Show Altered Values Only = true` (`F12` toggles `Show All Snapshot Fields`) and allows comparing the selected version against either the immediate predecessor `v(N-1)` or any chosen earlier baseline version (`Version 1..N-1`) via `selectCompareBaselineIndex`.
   - `DataExchangeService.getEditLogSummary`, `ExchangeService.getEditLogSummary`, and `MigrationAndExchangeComponent` support filtering the TallyAudit / Edit Log Activity Summary across `ALL`, `VOUCHER`, `LEDGER`, and `STOCK_ITEM` (reading `mc_edit_log_version` version counts and `actor_username` across all three entity types) by `onlyAltered` (`true`/`false`) and `username` (`EditLog User Name`).
3. **Client/Server Data Synchronization (`Alt+Z > Client/Server Sync`, `0x1410a5fd8` `SVClientRule`, `0x1410a5ff8` `SVServerRule`, `0x1410a6038` `SVSyncRuleType`, `0x1410e9390` `Client and Server companies have same GUID`, `0x141454090` `SyncAlterID`)**:
   - `MigrationAndExchangeComponent` (`IMPORT_EXPORT` tab) exposes a Client/Server Synchronization Rules (`Alt+Z`) panel supporting rule creation (`ruleName`, `ruleType`: `FULL_SYNC` | `MASTER_SYNC` | `TRANSACTION_SYNC`, `direction`: `BOTH_WAYS` | `SEND_ONLY` | `RECEIVE_ONLY`, `targetCompanyId`, `targetCompanyGuid`, `targetCompanyName`, `serverUrl`, `masterDuplicateRule`, `overwriteVoucherByGuid`), client/server self-sync GUID collision rejection (`0x1410e9390`, `422 MC-SYNC-SAME-COMPANY-GUID`), rule listing with `lastSyncAlterId` watermark, and one-click `Synchronise Now (Alt+Z)` execution (`POST /exchange/sync-rules/{ruleId}/execute`).
4. **Dashboard Tiles Export (`$$DashboardTotalTiles`, `DASHBOARDTYPE`, `DASHBOARDREPORTTYPE`, `0x14110e740`)**:
   - Endpoint: `POST /api/v1/companies/{companyId}/exports/dashboard-tiles`
   - Computes and exports the canonical TallyPrime Gold Dashboard tiles for `[fromDate, toDate]`:
     - `TRIAL_BALANCE_SUMMARY` (Total Opening/Period/Closing Debit & Credit, Zero-Variance flag)
     - `CASH_AND_BANK_SUMMARY` (Cash-in-Hand and Bank Accounts closing balances)
     - `RECEIVABLES_AND_PAYABLES` (Sundry Debtors & Sundry Creditors closing totals)
     - `INVENTORY_VALUATION_SUMMARY` (Active stock item count and closing stock value)
     - `GST_LIABILITY_AND_UNCERTAIN_TRANSACTIONS` (Output tax, Input ITC, Net payable, and count of Uncertain transactions with missing GSTIN/HSN)
   - Supports `format`: `JSON`, `HTML`, or `ASCII`.

### 9.5 Loop 5 — Financial & Inventory Reports (Scale Factors & Vertical Balance Sheet), Statutory GSTR-1/2B/3B/9 & IMS Reset, Auto-BRS, Multi-Account Reports, Dashboard Tiles & Company Data Verification (`fnd_5`, `fnd_9`, `ev_c5b4da3d...`, `ev_8446640c...`, `ev_e3db1dc0...`, `ev_de5067f6...`, `ev_5250d3fb...`, `ev_ec9f2835...`, `ev_06802999...`, `ev_434dc78d...`, `ev_a53981b1...`)
1. **Financial & Inventory Report Scale Factors & Vertical Balance Sheet (`0x14119ddd0` `Vertical / Horizontal Balance Sheet`, `0x14115d980` `scale factor`, `0x141239590`, `0x14107c090`–`0x14107c278` `Hundreds`, `Thousands`, `Lakhs`, `Millions`, `Crores`)**:
   - `BalanceSheetComponent` supports both `HORIZONTAL` (`Liabilities` vs `Assets`) and `VERTICAL` (`I. Sources of Funds` vs `II. Application of Funds`) forms (`Ctrl+H`), honoring session line removal/restoration (`Alt+R` / `Alt+U` / `Ctrl+U`) across both forms.
   - `BalanceSheetComponent` and `ProfitAndLossComponent` support TallyPrime's **Scale Factor** (`DEFAULT`, `HUNDREDS`, `THOUSANDS`, `LAKHS`, `MILLIONS`, `CRORES`) computed via exact `ExactDecimal` fixed-point scaling.
2. **Statutory GSTR-3B Multi-GSTIN Selector & IMS Reset (`0x14108a528`–`0x14108ad80`, `0x1410926b8` `IMS Download`, `0x1410926d8` `IMS Upload`, `0x1410926f0` `IMS Reset`, `0x14113b218` `IMSRESET`)**:
   - `Gstr3bDashboardComponent` exposes a Multi-GSTIN registration selector (`selectedRegistrationId`, `ensureRegistrationsLoaded`, `onRegistrationChange`) matching `Gstr1DashboardComponent`.
   - `ImsWorkbenchComponent` supports `IMS Reset` (`quickReset` / `NO_ACTION`), resetting an `ACCEPT`, `REJECT`, or `PENDING` supplier inward invoice back to `NO_ACTION` with `itcReductionAmount = "0.00"` and `remarks = null`.
3. **Multi-Account Reports (`Alt+E > Multi Account Reports`: `CONFIRMATION_OF_ACCOUNTS` & `REMINDER_LETTER`) with 4-Slab Bill-Wise Ageing**:
   - `DataExchangeService.exportMultiAccountReport` computes each party's open bill-wise ageing slabs (`0–30`, `31–60`, `61–90`, `>90` days) from `mc_bill_allocation` (`NEW_REF`/`ADVANCE` net of `AGST_REF`) as of `toDate` (attributing any unallocated ledger residual to `0–30` days) and renders all 4 ageing slabs in `JSON`, `HTML`, and `ASCII`.
   - `MigrationAndExchangeComponent` (`IMPORT_EXPORT` tab) exposes an interactive **Multi-Account Reports (`Alt+E`)** workbench to run and preview `CONFIRMATION_OF_ACCOUNTS` and `REMINDER_LETTER` across all active party ledgers.
4. **Interactive Dashboard Tiles Workbench (`Add Tile` `0x14113f1c8`, `Remove Tile` `0x14113f1e0`, `Expand Tile` `0x14113f1f8`, `SVDBVerticalView` `0x1410c7ef8`, `SVAddTileReportName` `0x1410c7638`)**:
   - `MigrationAndExchangeComponent` (`IMPORT_EXPORT` tab) exposes an interactive **Dashboard Tiles Workbench & Export** card supporting `Add Tile`, `Remove Tile`, `Expand Tile`, `Vertical View (SVDBVerticalView)` toggle, and export in `JSON`, `HTML`, or `ASCII` (`POST /api/v1/companies/{companyId}/exports/dashboard-tiles`).
5. **Company Data Verification Utility (`Alt+Y > Data Verification`, `0x1410c6340`, `0x141090bb0`)**:
   - Endpoint: `GET /api/v1/companies/{companyId}/exchange/data-verification` (and interactive UI card in `MigrationAndExchangeComponent`)
   - Performs a comprehensive read-only health & exception audit across the company's books:
     - `trialBalanceDifference`: Exact difference between total closing DR and CR across all ledgers.
     - `unbalancedVoucherCount`: Count of posted vouchers where `total_debit != total_credit` (`0x141090bb0`).
     - `unitMismatchCount`: Count of inventory lines with missing or mismatched UOM (`0x14108eef0`).
     - `missingHsnOrGstinCount`: Count of taxable stock items missing HSN/SAC or B2B party ledgers with invalid GSTIN length/check-digit.
     - `pendingImportExceptionCount`: Count of unresolved rows in the Import Exceptions register (`0x1410c6340`).
     - `readyForSplitOrSync`: `true` when `unbalancedVoucherCount == 0`, `unitMismatchCount == 0`, and `pendingImportExceptionCount == 0`.
