# Gate G2 — Foundation (`G2-F`), Accounting & Voucher Engine (`G2-J`), and Migration/Exchange (`G2-I`) Verification Evidence

- **Verification Timestamp**: `2026-11-15T04:20:59+05:30`
- **Target Repository Root**: `/Users/pushparaj/projects/Money_Craft/moneycraft-target`
- **Immutable Reference Root**: `/Users/pushparaj/projects/Money_Craft/argon_workspace`
- **PostgreSQL 18 Container Digest**: `postgres:18.6-bookworm@sha256:f1ea2a1a4fb7b68d4836be1fdcb8a6466a89b072158bf9d97663600538c294c5`
- **Toxiproxy Container Digest**: `ghcr.io/shopify/toxiproxy:2.11.0@sha256:cfdb2ca7ac7131a650d09db49782cc33e5c5d97fd3be5b3a6e27cd13d0ee8d57`
- **Gate G2 (`G2-F` / `G2-J` / `G2-I` Backend & DB Invariants) Status**: `PASS`

---

## 1. Reproducible Verification Commands & Summary Counts

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="/Users/pushparaj/.local/node/bin:/opt/homebrew/opt/maven/bin:$JAVA_HOME/bin:$PATH"
cd /Users/pushparaj/projects/Money_Craft/moneycraft-target

mvn -B verify -pl qa
python3 tools/verify_manifest.py
python3 tools/check_zero_licensing.py
```

| Module / Gate Tool | Runner | Suites | Tests | Failures | Errors | Skipped | Status |
| :--- | :--- | ---: | ---: | ---: | ---: | ---: | :--- |
| `moneycraft-reader` | Maven Surefire (`3.5.6`) | `3` | `9` | `0` | `0` | `0` | `PASS` |
| `moneycraft-server` | Maven Surefire (`3.5.6`, Real PG18.6) | `10` | `52` | `0` | `0` | `0` | `PASS` |
| `moneycraft-qa` | Maven Failsafe (`3.5.6`, Real PG18.6 + Toxiproxy + Dual-WAR) | `2` | `8` | `0` | `0` | `0` | `PASS` |
| `tools/verify_manifest.py` | Python 3 Static Gate (`338` files scanned) | — | — | `0` | `0` | — | `PASS` |
| `tools/check_zero_licensing.py` | Python 3 Static Gate (`353` files scanned) | — | — | `0` | `0` | — | `PASS` |
| `maven-checkstyle-plugin` | Checkstyle `14.4.0` (`moneycraft-checkstyle.xml`) | — | — | `0` | `0` | — | `PASS` |

---

## 2. Acceptance Criteria Matrix (`QUAL-005` Honest Reporting)

| Acceptance ID | Requirement Summary | Verification Suite & Method | Status |
| :--- | :--- | :--- | :--- |
| `AT-MIG-001..003` | Zero-framework/zero-network standalone `.1800` reader parses temp copies of `Company 100000` (`ABC & Co`, `28` groups, `24` ledgers, `24` voucher types, `13` vouchers, `INR 3,65,376.00` gross turnover, `INR 3,38,580.00` net TB, `INR 80,155.41` Net Profit); `argon_workspace/Source/` stays `0444`/`0555` untouched | `ReaderArchitecturalPurityTest`, `SectorAndShadowVerificationTest`, `Company100000AndParityGoldenReconciliationTest` | `PASS` |
| `AT-MIG-004..005` | Clean-room `.1800` staging snapshot -> zero-variance proof (`0.00` TB difference) -> PostgreSQL 18 promotion with `MIGRATE_IN` `Edit Log v1` baseline and preserved `"SENTHIL  ENTERPRISES"` double-space canonical name | `ReportingBankingMigrationExchangeAndPdfTest#cleanRoom1800SnapshotStagingProofAndZeroVariancePromotionCompany100000` | `PASS` |
| `AT-EXG-001..004` | Tally `<ENVELOPE>` XML (`-Dr/+Cr` sign parity, pure ASCII XML comments, `&#4; Primary` decoding, GUID replay dedup), Rel 7.2 `JSONEx` (`version: 1`), and OpenXML `.xlsx` round-trip exchange with `0.00` Trial Balance variance | `ReportingBankingMigrationExchangeAndPdfTest#xmlEnvelopeJsonExAndExcelRoundTripExchangeWithZeroVariance` | `PASS` |
| `AT-SEC-001..015` | Dual-PG18 (`authority` `ma_*` vs. `business` `mc_*`), `Argon2id` (`m=65536, t=3, p=1`), WebAuthn Passkeys, OIDC PKCE `S256`, scoped `mc_sk_*` Service API Keys, Tink AES-GCM / AES-SIV envelope encryption, `XSRF-TOKEN` CSRF enforcement, `Content-Security-Policy` per-request nonces, and `FORCE ROW LEVEL SECURITY` tenant isolation | `FoundationSecurityAndTenantIsolationTest`, `JcsAndCryptoUnitTest`, `OutboundConnectorRegistryTest` | `PASS` |
| `AT-GEN-003` | Zero license keys, zero seat/company/voucher caps, zero `.lic` parsers, zero external font CDNs, and zero telemetry beacons | `tools/check_zero_licensing.py` (`353` files verified), `OutboundConnectorRegistryTest` | `PASS` |
| `AT-FND-DR-01` | Unbroken `MC-AUDIT-2` hash chain + cross-cluster `ma_receipt` / `mc_admin_receipt_mirror` cryptographic parity + split-brain divergence `QUARANTINE` (`HTTP 503 MC-SYSTEM-QUARANTINED`) and owner recovery ceremony | `FoundationSecurityAndTenantIsolationTest#auditChainVerificationSplitBrainReceiptDivergenceQuarantineAndRecoveryCeremony`, `FaultProxyIdempotencyAndAuditInvariantIT#adminAuditVerifyConfirmsUnbrokenMcAudit2ChainAndCrossClusterAdminReceiptParity` | `PASS` |
| `AT-ACC-001..004` | Every new company seeds all `28` predefined groups, `Cash`, `Profit & Loss A/c`, `24` voucher types, `INR`, and `Primary Cost Category`; system groups/ledgers protected from deletion/rename; `mc_account_group` cycle guard rejects cycles (`422 MC-ACC-GROUP-CYCLE`) | `AccountingMastersAndCoaTest` (`4` tests) | `PASS` |
| `AT-ACC-005..014` | PostgreSQL 18 `DEFERRABLE INITIALLY DEFERRED` trigger `trg_mc_journal_double_entry_balance` rejects unbalanced (`MC-ACC-UNBALANCED-JOURNAL`), zero-sum, single-line, scale-overflow (`MC-ACC-SCALE-OVERFLOW`), and closed-period commits; `UPDATE`/`DELETE` on `mc_entry` blocked at both `mc_app_runtime` privilege boundary and superuser `MC-IMMUTABLE-RECORD` trigger boundary; `Contra`/`Payment`/`Receipt`/`Journal`/`Sales`/`Purchase`, `OPTIONAL`/`MEMORANDUM` `0`-GL isolation, `FOUR_EYES`, `Alt+X` cancel, Bill-Wise (`NEW_REF`/`AGST_REF`/`ADVANCE`/`ON_ACCOUNT`), and Multi-Currency `unadjustedForexDelta` (`1500.00`) verified | `VoucherPostingAndInvariantsTest` (`4` tests), `FaultProxyIdempotencyAndAuditInvariantIT#updateAndDeleteAgainstMcEntryMcAuditEventAndMcEditLogVersionFailClosedAtPrivilegeAndTriggerBoundaries` | `PASS` |
| `AT-RETRY-01` | Toxiproxy Business PG18 TCP `RST` fails closed with `0` partial `mc_entry` rows; `MidResponseTcpFaultProxy` severs HTTP `201` socket mid-response after commit, and RFC 8785 JCS key-reordered JSON replay returns exact cached receipt (`X-Idempotency-Replayed: true`, `0` duplicate `mc_entry` rows); mutated amount with same `Idempotency-Key` fails closed (`409 MC-IDEMPOTENCY-PAYLOAD-MISMATCH`) | `FaultProxyIdempotencyAndAuditInvariantIT` (`Order(1)..Order(3)`), `VoucherConcurrencyAndIdempotencyTest` | `PASS` |
| `AT-CONC-01` | `32` concurrent threads posting overlapping ledgers & `MULTI_USER_AUTO` number series commit with `0` deadlocks (`MCJ1-TXN-01` deterministic lock order) and gap-free voucher numbers | `VoucherConcurrencyAndIdempotencyTest#thirtyTwoConcurrentThreadsPostOverlappingLedgersWithZeroDeadlocksAndGapFreeNumbers` | `PASS` |
| `AT-UI-BRAND-01`, `AT-UI-NAV-01`, `AT-UI-GRID-01` | Browser-level 3-engine (Chromium/Firefox/WebKit) Playwright mouseless UI & `Alt+G` stack navigation checks | Server CSP nonce & PDFBox 3 font/glyph checks `PASS`; 3-browser UI Playwright spec execution | `NOT RUN` (outside `qa/` JVM Failsafe scope) |
| `AT-OPS-DOCKER-01` | Full Docker Compose stack (`gateway` + `ui` + `api` + `pg-backup` + Dual-PG18) live rehearsal | Verified separately by DevOps release stream (`mc-devops-release-engineer`) | `NOT RUN` (outside `qa/` JVM Failsafe scope) |

---

## 3. FaultProxy & Adversarial Invariant Proof (`FaultProxyIdempotencyAndAuditInvariantIT`)

Executed against real `postgres:18.6-bookworm` Dual-Cluster (`authority-pg` + `business-pg`) and `ghcr.io/shopify/toxiproxy:2.11.0` (`business-pg-fault-proxy` on `:8666 -> business-pg:5432`) plus `MidResponseTcpFaultProxy`:

1. **`toxiproxyBusinessPgResetFailsClosedWithoutPartialEntriesAndSucceedsOnRetry` (`12.495s`) — `PASS`**:
   - Injected bidirectional TCP `RST` toxics (`resetPeer` `UPSTREAM` + `DOWNSTREAM`) on Business PostgreSQL 18 via `ToxiproxyClient`.
   - Verified `POST /api/v1/companies/{id}/vouchers` failed closed (`HTTP >= 400`) with **`0` `mc_entry` rows** persisted.
   - Removed toxics, re-acquired `AuthorityGuard` writer lease, retried with identical `Idempotency-Key`, and confirmed `HTTP 201 POSTED` (`2` `mc_entry` rows, `SUM(DR) == SUM(CR) == 50,000.00`).
2. **`faultProxyMidResponseDropAndJcsReorderedReplayReturnCachedReceiptWithZeroDuplicateEntries` (`0.144s`) — `PASS`**:
   - Armed `MidResponseTcpFaultProxy` (`SO_LINGER = 0` TCP `RST` after first `16` bytes of HTTP `201` response, after PostgreSQL 18 committed `mc_entry` + `mc_retry`).
   - Replayed `POST /api/v1/companies/{id}/vouchers` with identical `Idempotency-Key` and **all JSON object keys reordered** (`RFC 8785` JCS canonicalization).
   - Confirmed `HTTP 201`, `X-Idempotency-Replayed: true`, `ETag: "v1"`, identical `voucherNumber` and `id`, and **`0` duplicate `mc_journal` or `mc_entry` rows** (`SUM(closing_dr) == SUM(closing_cr) == 175,000.00`).
3. **`mutatedIdempotencyPayloadConflictAndExcessScaleFailClosed` (`0.067s`) — `PASS`**:
   - Replaying the same `Idempotency-Key` with amount mutated by `0.01` (`125000.00` -> `125000.01`) rejected with `HTTP 409` (`MC-IDEMPOTENCY-PAYLOAD-MISMATCH`).
   - Posting `100.125` (`3` decimal places on `INR` scale-`2` company) rejected with `HTTP 422` (`MC-ACC-SCALE-OVERFLOW`).
4. **`updateAndDeleteAgainstMcEntryMcAuditEventAndMcEditLogVersionFailClosedAtPrivilegeAndTriggerBoundaries` (`0.130s`) — `PASS`**:
   - Verified `PUT /vouchers/{id}` (`If-Match: "v1"` -> `ETag: "v2"`) appends compensating reversal + repost entries (`SUM(closing_dr) == SUM(closing_cr) == 180,000.00`) while stale `If-Match: "v1"` returns `HTTP 412`.
   - Confirmed `UPDATE` and `DELETE` on `mc_entry`, `mc_audit_event`, and `mc_edit_log_version` fail closed for runtime role `mc_app_runtime` (`DataAccessException` privilege denial) **and** for PostgreSQL superuser `postgres` (`SQLException: MC-IMMUTABLE-RECORD`).
   - Confirmed direct SQL commit of unbalanced `mc_entry` (`500.00 DR` vs. `499.99 CR`) aborts at transaction commit via `trg_mc_journal_double_entry_balance` (`MC-ACC-UNBALANCED-JOURNAL`).
5. **`adminAuditVerifyConfirmsUnbrokenMcAudit2ChainAndCrossClusterAdminReceiptParity` (`0.030s`) — `PASS`**:
   - `GET /api/v1/admin/audit/verify` returned `verified: true`, `0` leaked credentials/hashes in `mc_audit_event.redacted_payload`, and byte-for-byte SHA-256 `receipt_hash` / `prev_receipt_hash` chain parity between `ma_receipt` (Authority DB) and `mc_admin_receipt_mirror` + `mc_installation_fence.last_mirrored_receipt_seq` (Business DB).
