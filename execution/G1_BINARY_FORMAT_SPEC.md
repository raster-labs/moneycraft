# Gate G1 — Clean-Room `.1800` Binary Format & Companion Snapshot Specification

- **Document ID**: `MC-EXEC-G1-BINARY-SPEC`
- **Version**: `1.0.0`
- **Status**: Gate G1 Authoritative Clean-Room Specification
- **Requirements Covered**: `GEN-001`, `GEN-002`, `SEC-015`, `MIG-001`–`MIG-007`, `AT-MIG-001`–`AT-MIG-003`
- **Target Module**: `com.raster.moneycraft.reader` (`moneycraft-target/reader/`)

---

## 1. Clean-Room Methodology & Scope Boundaries

This specification documents the physical sector, page, B-tree link, and Tag-Length-Value (TLV) record layout of TallyPrime Release `7.0` (`1.1.7.0`) through Release `7.2` (`1.1.7.2`) `.1800` company data directories, together with companion Tally canonical XML `<ENVELOPE>` snapshots.

1. **Static-Only Evidence (`GEN-001`, `GEN-002`)**: Derived strictly via read-only byte-level inspection of immutable `.1800` fixtures (`Company 100000` — *ABC & Co*) and the 6-step storage mutation differential matrix (`windows_lab_oracles/synthetic/1800_diffs/storage_mutation_matrix.json`, `09_open_close_70_to_72_1800_diff.json`). No proprietary `.exe` binaries were executed on the host, and no proprietary source or TDL code was copied.
2. **License File Exclusion (`SEC-015`)**: Any file whose filename (case-insensitive) ends with `.lic` or contains `lic` is unconditionally skipped by `Sector512PageReader` and `BinaryStoreScanner` prior to opening file descriptors.
3. **Standalone Reader Isolation (`MIG-003`)**: The Java 25 `moneycraft-reader` module depends exclusively on `java.base` at compile and runtime (zero Spring, Hibernate/JPA, Jakarta Servlet, Jackson, or network libraries) and performs all monetary and quantity arithmetic exclusively with `java.math.BigDecimal`.

---

## 2. Physical Directory & Store Inventory

A TallyPrime company snapshot resides in a 6-digit zero-padded directory (for example, `100000/`) containing `512`-byte sector-aligned `.1800` data stores and lightweight `.TSF` coordination files.

### 2.1 Primary `.1800` Data Stores

| Store Filename | Store Class | Primary Responsibility | Verified Mutation Trigger (`S0..S5`) |
| :--- | :--- | :--- | :--- |
| `Company.1800` | OODBMS Store | `<COMPANY>` master record: Name, Mailing Name, GUID, State, Country, Pincode, `STARTINGFROM`, `BOOKSFROM`, `AUDITEDUPTO`, feature flags, GST/Edit Log company settings. | Mutates on company creation/upgrade (`7.0 -> 7.2`) and `S0` (`ISVCHEDITLOGAPPLICABLE`). |
| `CmpSave.1800` | Shadow Mirror | Clean-shutdown shadow commit copy of `Company.1800`. Must match `Company.1800` byte-for-byte (`SHA-256` identical) on a cleanly closed company (`MIG-002`). | Identical to `Company.1800` across clean closes; divergence signals `DIRTY_SHUTDOWN_DETECTED`. |
| `Manager.1800` | OODBMS Store | Master directory for all 35 `<CMPINFO>` master classes (`Group`, `Ledger`, `VoucherType`, `Currency`, `Unit`, `Godown`, `StockGroup`, `StockCategory`, `StockItem`, `CostCategory`, `CostCentre`, `TaxUnit`, `VoucherNumberSeries`). | Mutates in `S1` (Ledger creation) and `S2` (UOM + Stock Item creation). |
| `LinkMgr.1800` | OODBMS Store | Bill-wise receivable/payable reference table (`billId -> billRefName`, due dates, opening/pending amounts) and cross-object relational links. | Referenced by `TranMgr.1800` `BILLALLOCATIONS.LIST` (`wireType = 0x0500`). |
| `LinkDataMgr.1800` | OODBMS Store | Auxiliary link payload store for inventory/uom/statutory cross-references. | Mutates in `S2` when linking a `StockItem` to its `BASEUNITS` UOM. |
| `TranMgr.1800` | OODBMS Store | Posted, optional, cancelled, and soft-deleted `<VOUCHER>` headers, double-entry ledger lines, inventory allocations, bill allocations, and embedded MCA Edit Log (`role = 0x05`) version snapshots. | Grows monotonically across `S3` (Post v1), `S4` (Alter v2), and `S5` (Cancel v3) — prior sector images are retained rather than truncated. |
| `Aggr.1800` | OODBMS Store | Pre-aggregated periodic ledger and group balance rollups accelerating Trial Balance / BS / P&L queries. | Recomputed in `S3`, `S4`, and `S5` whenever voucher postings change. |
| `Index.1800` | Weighted B-Tree | Secondary name/GUID/lookup weighted B-tree index (`"Tally Index"` magic). | Rebalanced on master and voucher mutations (`S1`–`S5`). |
| `ExtMngr.1800` | OODBMS Store | Extended statutory/compliance metadata manager (GST registration state, MCA Edit Log / audit extensions). | Present in every `.1800` company directory (`512`B header-only when empty). |
| `ExtDataMgr.1800` | OODBMS Store | Extended statutory/audit variable-length data payloads. | Paired with `ExtMngr.1800`. |
| `VchStatus.1800` | OODBMS Store | Per-voucher e-Invoice / e-Way Bill / GST return / reconciliation status flags. | Paired with `TranMgr.1800` `MASTERID`s. |
| `StatStatus.1800` | OODBMS Store | Periodic GST/TDS statutory return filing & uncertain-transaction status buckets. | Paired with statutory return periods. |
| `SecTran.1800` | OODBMS Store | Secondary/auxiliary transaction link store. | Header-aligned (`512`B minimum). |
| `AddlCmp.1800` | OODBMS Store | Additional company preferences & feature configuration store. | Mutates on 7.0 -> 7.2 format touch. |
| `CmpNotification.1800` | OODBMS Store | Notification & background activity queue store. | Header-aligned (`512`B minimum). |
| `CmpSchMetaData.1800` | OODBMS Store | Schema metadata & version migration descriptor store. | Header-aligned (`512`B minimum). |

### 2.2 Coordination `.TSF` Files (Non-Data)

Files such as `Access.TSF`, `Active.TSF`, `TranMgr.TSF`, `PndgList.TSF`, `ReComp.TSF`, `SumTran.TSF`, `Traninf.TSF`, and `UpdMngr.TSF` are transient runtime coordination/lock markers (`0`–`56` bytes) and are not sector-aligned `.1800` stores.

---

## 3. `512`-Byte Sector Alignment & Sector-0 Header Layout

Every `.1800` file is strictly partitioned into fixed **`512`-byte (`0x200`) sectors**:

$$\text{fileSize} \ge 512 \quad \land \quad (\text{fileSize} \bmod 512) = 0$$

Any `.1800` file failing `fileSize >= 512` or `fileSize % 512 == 0` is rejected fail-closed as `TRUNCATED_OR_MISALIGNED_SECTOR_FILE`.

### 3.1 Standard OODBMS Store Sector-0 Layout (`Sector 0`, Bytes `0..511`)

All standard `.1800` stores (`Company`, `CmpSave`, `Manager`, `LinkMgr`, `LinkDataMgr`, `TranMgr`, `Aggr`, `ExtMngr`, `ExtDataMgr`, `VchStatus`, `StatStatus`, `SecTran`, `AddlCmp`, `CmpNotification`, `CmpSchMetaData`) share a uniform little-endian Sector-0 layout:

| Byte Offset | Type | Field Name | Invariant / Verified Values |
| :--- | :--- | :--- | :--- |
| `0..1` | `uint16_le` | `releaseStoreMagic` | Store-class & release magic word:<br>• **Release 7.0 (`1.1.7.0`)**: `0xAD59` (`59 AD`) for standard stores; `0x4311` (`11 43`) for `Manager.1800` & `ExtMngr.1800`.<br>• **Release 7.2 (`1.1.7.2`)**: `0x0446` (`46 04`) for standard stores; `0xEA0E` (`0E EA`) for `Manager.1800` & `ExtMngr.1800`. |
| `2..3` | `uint16_le` | `rollingChecksum16` | Rolling 16-bit header/store checksum; mutates on every sector/header write while `[0..1]` stays constant per release. Together `[0..3]` forms `headerSignature32`. |
| `4..7` | `uint32_le` | `reservedZero` | Must equal `0x00000000`. |
| `8..11` | `uint32_le` | `dataSectorCount` | Exact count of `512`-byte data sectors following Sector 0:<br>$$\text{dataSectorCount} = \frac{\text{fileSize}}{512} - 1$$<br>Header-only (`512`-byte) stores have `dataSectorCount == 0`. Mismatch triggers `SECTOR_COUNT_MISMATCH`. |
| `12..15` | `uint32_le` | `allocatedSectorCount` | Count of allocated non-free data sectors (`<= dataSectorCount`). |
| `16..19` | `uint32_le` | `rootDirectorySector` | Sector index of root directory / B-tree entry sector (`0` when empty). |
| `20..23` | `uint32_le` | `firstDataSector` | `1` when store has data sectors, `0` when empty. |
| `24..27` | `uint32_le` | `highWaterSector` | Highest initialized sector index (`<= dataSectorCount`). |
| `28..31` | `uint32_le` | `activeRootCount` | Count of active root object chains in the store. |
| `32..35` | `uint32_le` | `sentinel32` | Must equal **`0xFFFFFFFF`** (`ff ff ff ff`). Mismatch on a non-index `.1800` file triggers `INVALID_SECTOR0_SENTINEL`. |
| `36..39` | `uint32_le` | `pageFlags` | `0x00010000` (`65536`) on initialized stores (`0` on some untouched `512`B stores). |
| `40..103` | `byte[64]` | `freeListBuckets` | Sector free-list head pointers (`0x00` when clean). |
| `104..107` | `uint32_le` | `schemaGeneration` | Store generation / migration counter; increments on 7.0 -> 7.2 touch (`0x01 -> 0x02` or `0x02 -> 0x03`). |
| `108..111` | `uint32_le` | `storeSubVersion` | Store schema sub-version (`1` or `2`). |
| `112..113` | `byte[2]` | `releaseStampBytes` | 2-byte release format tag (`0x46 0x6D` = `"Fm"` in Rel 7.0; mutates at bytes `112..113` on upgrade to Rel 7.2 as captured in `09_open_close_70_to_72_1800_diff.json`). |
| `114..175` | `byte[62]` | `reservedMetadata` | Zero-padded header metadata region. |
| `176..179` | `uint32_le` | `formatCapabilityWord` | `0x00020000` (`00 00 02 00`). |
| `180..511` | `byte[332]` | `headerPadding` | Zero-filled (`0x00`) to `512` bytes. |

### 3.2 `Index.1800` Weighted B-Tree Sector-0 Layout

`Index.1800` is a `512`-byte sector-aligned weighted B-tree index file identified by its ASCII magic string at bytes `13..23`:

- `0..3` (`uint32_le`): Release/index header signature (`0x3A3D4DC7` in Rel 7.0; `0x5CC90BCB` in Rel 7.2).
- `4..7` (`uint32_le`): Index format version (`0x00000001`).
- `13..23` (`ASCII[11]`): Exact literal `"Tally Index"`.
- `80..95` (`byte[16]`): Binary 16-byte Company UUID/GUID.

### 3.3 Shadow-Commit Invariant (`CmpSave.1800 == Company.1800`)

During active company mutation, Tally writes dirty company header/profile state to `Company.1800` and synchronizes `CmpSave.1800` only upon clean transactional close. Therefore:
1. Both `Company.1800` and `CmpSave.1800` **must** exist in a valid `.1800` company snapshot directory.
2. Their file sizes and full byte contents (`SHA-256`) **must** be identical. Any byte divergence raises `DIRTY_SHUTDOWN_DETECTED` and aborts snapshot promotion fail-closed (`MIG-002`, `AT-MIG-002`).

---

## 4. Data Sector (`Sector >= 1`) Framing & Linked Stream Reassembly

Every data sector `s` (`1 <= s <= dataSectorCount`) in a standard OODBMS store consists of a **28-byte Page Header (`[0..27]`)** followed by a **484-byte Payload Chunk (`[28..511]`)**:

| Offset | Type | Field | Description |
| :--- | :--- | :--- | :--- |
| `0..3` | `uint32_le` | `pageChecksum` | 32-bit sector checksum (`0` on unused/free sectors). |
| `4..7` | `uint32_le` | `masterId` | 1-based owning object `MASTERID` within the store (`1..227` in `Manager.1800`, `1..16` in `TranMgr.1800`, `1` in `Company.1800`). |
| `8..11` | `uint32_le` | `chainSectorSpan` | Total sectors in this logical stream on the **head** sector (`> 0`); `0` on continuation sectors. |
| `12..15` | `uint32_le` | `nextSector` | Next `1`-based sector index in the linked stream, or `0` if this is the terminal chunk. |
| `16..19` | `uint32_le` | `pageRole` | Logical page type:<br>• `0x0B`: Object Root Directory Page<br>• `0x42`: Primary/Secondary TLV Attribute & Collection Stream<br>• `0x05`: Embedded MCA Edit Log (`VOUCHERAUDIT`) Version Stream<br>• `0x03`, `0x0A`: Auxiliary B-tree / index descriptor pages |
| `20..23` | `uint32_le` | `subStreamId` | `0xFFFFFFFF` on Object Root (`role = 0x0B`); `1` = Primary Header Stream; `2` = Child Collection / Ledger-Lines Stream; `3+` = Extended Streams. |
| `24..27` | `uint32_le` | `streamDescriptor` | `0x00000000` on Root `0x0B`; `0x00FFFF0B` on `0x42` TLV streams; `(masterId << 8) | roleTag` on `0x05` Edit Log streams. |

**Stream Reassembly Algorithm**:
Given `headSector` (`1 <= headSector <= dataSectorCount`), concatenate `sector[28..512]` while following `nextSector` until `nextSector == 0`. A `BitSet` / `Set<Integer>` of visited sectors enforces loop-free termination against malformed cyclic sector pointers.

### 4.1 Object Root Framing (`pageRole == 0x0B && subStreamId == 0xFFFFFFFF && chainSectorSpan > 0`)

1. **`Company.1800`**:
   - Sector `1` (`role = 0x0B, masterId = 1`) points at `+12` (`uint32_le`) to Sector `2` (`role = 0x42, subStreamId = 1`).
   - Reassembling Sector `2` yields the `<COMPANY>` TLV attribute stream starting directly at payload offset `0`.
2. **`Manager.1800`**:
   - Each master object has a Root stream (`role = 0x0B, subStreamId = 0xFFFFFFFF, chainSectorSpan > 0`).
   - Offset `0..3` (`uint32_le`): `rootHeaderLen` (`16`, `20`, or `24` bytes; offsets `12..rootHeaderLen` hold `uint32_le` sub-stream sector pointers).
   - Offset `rootHeaderLen .. rootHeaderLen + 21` (`22` bytes): Intrusive B-tree & doubly-linked directory node (`uint16_le nodeFlags` + `4 x uint32_le` `[prevSector, leftSector, rightSector, nextSector]` + `uint32_le pad`).
   - Offset **`tlvStart = rootHeaderLen + 22`**: Start of the object's TLV attribute stream!
3. **`TranMgr.1800`**:
   - Each voucher root (`role = 0x0B, subStreamId = 0xFFFFFFFF, chainSectorSpan > 0`) stores:
     - `sub1Sector = uint32_le` at payload offset `12`: Voucher Header TLV stream (`role = 0x42, subStreamId = 1`), starting at offset `0`.
     - `sub2Sector = uint32_le` at payload offset `16`: Voucher Entries & Sub-Allocations TLV stream (`role = 0x42, subStreamId = 2`), starting at offset `0`.
   - Sectors with `role == 0x05` and `chainSectorSpan > 0` belonging to `masterId` store the append-only MCA Edit Log (`VOUCHERAUDIT`) historical version snapshots for that voucher.

---

## 5. Binary Tag-Length-Value (TLV) Record Encoding

Every TLV attribute begins with a **6-byte little-endian Tag Header**:

```text
+0..1 : uint16_le flags
+2..3 : uint16_le fieldId
+4..5 : uint16_le wireType
```

A stream or nested sub-object terminates cleanly upon encountering `flags == 0 && fieldId == 0 && wireType == 0` or reaching `limit`.

### 5.1 Tag Prefix Flag Bits (`flags`)

- `0x0400`: **Key Prefix 1 Present** — `4`-byte `uint32_le` index/sub-element key immediately follows the 6-byte tag header before the value payload.
- `0x0800`: **Key Prefix 2 Present** — second `4`-byte `uint32_le` composite key follows Key Prefix 1 before the value payload.
- `0x0080`: **Deemed Positive (Debit / Inward) Indicator** — set on `0x0900` currency fields when `ISDEEMEDPOSITIVE = Yes` (Debit).

### 5.2 Wire Types (`wireType`)

| `wireType` | Payload Size (after any `0x0400`/`0x0800` key prefix) | Semantic Type & Clean-Room Decoding Rule |
| :--- | :--- | :--- |
| `0x0100` | `0` bytes | **Boolean `true`** (`Yes`). Presence encodes `true`; absence encodes `false`. |
| `0x0200` | `4` bytes (`uint32_le`) | **Enumeration / Method Code** (e.g., GST taxability, numbering method, valuation code). |
| `0x0300` | `4` bytes (`int32_le`) | **`MASTERID` Foreign Key Reference** (`0` = `Primary` / unassigned; `> 0` references `masterId` in `Manager.1800`). |
| `0x0400` | `4` bytes (`uint32_le`) | **Unsigned 32-bit Scalar**. |
| `0x0500` | `4` bytes (`uint32_le`) | **`LinkMgr` Bill Reference ID** (references `masterId` in `LinkMgr.1800`). |
| `0x0600` | `4` bytes (`int32_le`) | **Signed 32-bit Integer** (`OBJECT_CLASS_ID`, `MASTERID`, `ALTERID`, sort position). |
| `0x0700` | `8` bytes (`byte[8]`) | **Compound Rate / Dated Scalar** (`uint32_le aux` + `uint32_le dateOrScale`). |
| `0x0800` | `8` bytes (`int64_le`) | **Fixed-Point Quantity / Rate Scalar**. |
| `0x0900` | `8` bytes (`int64_le`) | **Exact Scaled Currency Amount** in units of **`10^-5`** (`BigDecimal.valueOf(rawInt64, 5)`):<br>• Negative two's-complement (`rawInt64 < 0`) = **Debit** (`-471,400,000 -> -4714.00`)<br>• Positive (`rawInt64 > 0`) = **Credit** (`+495,000,000 -> +4950.00`). |
| `0x0A00`–`0x0C00` | `8` bytes (`byte[8]`) | **64-bit Timestamp / Audit / Forex Struct**. |
| `0x0D00` | `4` bytes (`uint32_le`) | **Tally Serial Date**: integer days elapsed since **`1899-12-31`** (Day `1` = `1900-01-01`, with no 1900 leap-year bug: `LocalDate.of(1899, 12, 31).plusDays(serial)`; e.g. `0xB420 = 46112 -> 2026-04-01`, `0xB4B9 = 46265 -> 2026-09-01`). |
| `0x0E00` | `8` bytes (`2 x uint32_le`) | **Date Range** (`[fromSerial, toSerial]`). |
| `0x0F00` | `2 + N` bytes | **UTF-16LE String**: `uint16_le byteLength` followed by `byteLength` bytes of UTF-16LE text (strip trailing `U+0000`). |
| `0x1000` | `2 + N` bytes | **Nested TLV Sub-Object / `.LIST` Collection**: `uint16_le byteLength` followed by `byteLength` bytes of nested TLV attributes. |
| `0x0021` | `16` bytes (`4 x uint32_le`) | **Intrusive B-Tree Node** (`[prev, left, right, next]`). |
| `0x0022` | `20` bytes (`5 x uint32_le`) | **Weighted Intrusive B-Tree Node** (`[prev, left, right, next, weight]`). |
| `0x0041` | `16` bytes | **Index Key Entry (16B)** in `LinkMgr` / `TranMgr` sub-streams. |
| `0x0042` | `20` bytes | **Index Key Entry (20B)** in `TranMgr` `subStreamId = 2`. |
| `0x0044` | `32` bytes | **Amount-Indexed Entry (32B)** in `TranMgr` `subStreamId = 2`. |

---

## 6. Decoded Schema Dictionary by Store

### 6.1 `Manager.1800` — `<CMPINFO>` 1-Based `OBJECT_CLASS_ID` (`fieldId = 0x0003, wireType = 0x0600`)

| `classId` | Tally Object Type | Verified Count in `100000` | Key Decoded Fields |
| :---: | :--- | :---: | :--- |
| `2` | `GROUP` | `28` | `parentId` (`0x0003/0x0300`), `LANGUAGENAME.LIST` (`0x0003/0x1000 -> 0x0002/0x0F00`), group behaviour flags inside `0x0963/0x1000`: `0x01FC` (`ISDEEMEDPOSITIVE`), `0x0963` (`ISSUBLEDGER`), `0x0964` (`ISREVENUE`), `0x0965` (`AFFECTSGROSSPROFIT`). |
| `3` | `LEDGER` | `24` | `parentId` (`0x0003/0x0300`), `alterId` (`0x01F8/0x0600`), `isBillWiseOn` (`0x0A2F/0x0100`), `openingBalance` (`0x0A29` or `0x0002` / `0x0900`), `LANGUAGENAME.LIST` (`0x0003/0x1000`), GSTIN/State strings (`0x0F00`). |
| `4` | `COSTCATEGORY` | `1` | Primary Cost Category (`Primary Cost Category`). |
| `5` | `COSTCENTRE` | `0` | Cost Centre masters. |
| `6` | `GODOWN` | `1` | `Main Location` (`parentId`, `LANGUAGENAME.LIST`). |
| `7` | `STOCKGROUP` | `0` | Stock Group hierarchy. |
| `8` | `STOCKCATEGORY` | `0` | Stock Category hierarchy. |
| `9` | `STOCKITEM` | `0` | Stock Item master, `parentStockGroupId` (`0x0003/0x0300`), base UOM ref, costing/valuation method. |
| `10` | `VOUCHERTYPE` | `33` (`24` active + `9` dormant) | `baseTypeId` (`0x0FA2/0x0600`): IDs `1..18` & `24..29` are Tally's **24 active standard voucher types**; IDs `19..23` (MRP planning) & `30..33` (`Stat Only *`, `Opening Balance`) are dormant system placeholders unless custom/enabled. Abbreviation at `0x01F7/0x0F00`. |
| `11` | `CURRENCY` | `1` | Base currency symbol (`₹`) and formal name (`INR`). |
| `12` | `UNIT` | `0` | Simple/compound Units of Measure (`UOM`). |
| `31` | `TAXUNIT` | `2` | Statutory Tax Units (`Default Tax Unit`, `Tamil Nadu Registration`). |
| `34` | `VOUCHERNUMBERSERIES` | `33` | `Default` numbering series linked to each `VOUCHERTYPE`. |

### 6.2 `Company.1800` — `<COMPANY>` Profile Attributes (Sector 2 Stream)

- `0x001B` (`0x0D00`): `STARTINGFROM` date; (`0x0F00`): `NAME` (`ABC & Co`).
- `0x001C` (`0x0D00`): `BOOKSFROM` date; (`0x0F00`): `BASICCOMPANYFORMALNAME`.
- `0x09C6` (`0x0D00`): `AUDITEDUPTO` date.
- `0x001D` (`0x0F00`): `ADDRESS` lines.
- `0x001E` (`0x0F00`): Canonical 36-char `GUID` (`5eeb242a-976c-44e3-898e-0423c32b464c`).
- `0x0066` (`0x0F00`): `EMAIL`; `0x0067` (`0x0F00`): `STATENAME`; `0x0068` (`0x0F00`): `PINCODE`; `0x00D2` (`0x0F00`): `COUNTRYNAME`.

### 6.3 `TranMgr.1800` & `LinkMgr.1800` — Vouchers, Double-Entry Lines & Bill Allocations

1. **Voucher Header (`sub1Sector`, `role = 0x42, subStreamId = 1`)**:
   - `0x00CB` (`0x0100`): **`ISDELETED`**. When present (`true`), the voucher was deleted (`mid = 14, 16` in `100000`) and **must** be excluded from active voucher counts and Trial Balance turnover, while remaining traceable in audit diagnostics.
   - `0x00CC` (`0x0100`): `ISCANCELLED`; `0x00CE` (`0x0100`): `ISOPTIONAL`.
   - `0x00CB` (`0x0300`): `voucherTypeMasterId` -> resolves `RawVoucherType` in `Manager.1800`.
   - `0x00CC` (`0x0300`): `partyLedgerMasterId` -> resolves party `RawLedger` in `Manager.1800`.
   - `0x0067` (`0x0D00`): `DATE`; `0x0068` (`0x0D00`): `EFFECTIVEDATE`.
   - `0x07D5` (`0x0F00`): `VOUCHERNUMBER`; `0x00CC` (`0x0F00`): `REFERENCE`; `0x00CD` (`0x0F00`): `NARRATION`; `0x05FA` (`0x0F00`): `PLACEOFSUPPLY`.
   - `0x0BBB` (`0x0600`): `MASTERID`; `0x0BBC` (`0x0600`): `ALTERID`.
2. **Voucher Ledger Lines (`sub2Sector`, `role = 0x42, subStreamId = 2`)**:
   - Each `fieldId = 0x2713` (`10003`), `wireType = 0x1000` represents one `ALLLEDGERENTRIES.LIST` line:
     - `0x0002` (`0x0100`): `isPartyLedger`; `0x0005` (`0x0100`): `isDeemedPositive` (`Debit` when `true`).
     - Nested `0x0002/0x1000 -> 0x0002/0x1000`:
       - `0x0002` (`0x0300`): `ledgerMasterId` -> resolves `RawLedger.name` in `Manager.1800`.
       - `0x0002` (`0x0900`): `amountSigned` in `10^-5` (`BigDecimal.valueOf(rawInt64, 5)`).
       - `0x01F6` (`0x1000`): `BILLALLOCATIONS.LIST` containing `0x0002` (`0x0500` `linkMgrBillId` -> resolved to bill name in `LinkMgr.1800` at tag `02 10 02 00 00 0F`) and `0x0002` (`0x0900` `billAmountSigned`).

---

## 7. Companion Tally XML `<ENVELOPE>` Canonical Snapshot Contract

In addition to direct `.1800` binary page extraction, `CompanionXmlSnapshotParser` ingests Tally canonical XML exports (single `<ENVELOPE>` files or multi-file oracle directories):

1. **Control-Character Entity Sanitization**: TallyPrime exports unescaped XML 1.0 illegal character references such as `&#4;` (`&#4; Primary`, `&#4; Not Applicable`). The parser strips `&#x00;`–`&#x1F;` (except tab/LF/CR) and strips leading `\u0004` markers from sentinel tokens (`Primary`, `Not Applicable`, `End of List`).
2. **Universal Sign Law (`MIG-005`)**:
   - Negative `<AMOUNT>` / `<OPENINGBALANCE>` = **Debit / Inward** (`DR`).
   - Positive `<AMOUNT>` / `<OPENINGBALANCE>` = **Credit / Outward** (`CR`).
3. **Consolidated Double-Entry Extraction**:
   - Uses `<ALLLEDGERENTRIES.LIST>` (when populated with `<LEDGERNAME>`) as the authoritative consolidated accounting journal per voucher, falling back to `<LEDGERENTRIES.LIST>` + `<ALLINVENTORYENTRIES.LIST>/<ACCOUNTINGALLOCATIONS.LIST>` only when `<ALLLEDGERENTRIES.LIST>` is absent/empty, preventing item-invoice double counting.
   - Extracts `<ALLINVENTORYENTRIES.LIST>` stock movements (`STOCKITEMNAME`, `ISDEEMEDPOSITIVE`, `ACTUALQTY`, `BILLEDQTY`, `RATE`, `AMOUNT`, `GODOWNNAME`, `BATCHNAME`) and evaluates `Avg. Cost` and `FIFO` closing stock in `MathContext.DECIMAL128` (`RoundingMode.HALF_UP` to 2 decimal places at statement presentation).

---

## 8. Ground-Truth Oracle Verification Benchmarks

| Benchmark Dataset | Groups | Ledgers | Active Voucher Types | Posted Vouchers | Gross Turnover (`DR == CR`) | Ledger Closing TB (`DR == CR`) | Closing Stock | Gross Profit | Net Profit |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **`ABC & Co` (`100000` Binary `.1800` & XML)** | `28` | `24` | `24` | `13` | `365376.00` | `338580.00` *(Top-Group TB: `302843.94`)* | `0.00` | `80155.70` | `80155.41` |
| **`MoneyCraft Parity Benchmark` (`parity_golden` XML)** | `28` | `14` | `24` | `9` | `1027760.00` | `760448.00` | `45685.71` | `49385.71` | `4385.71` |
