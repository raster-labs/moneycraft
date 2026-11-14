# MoneyCraft 1.0.0 — Clean-Room Enterprise Accounting & Statutory ERP

MoneyCraft 1.0.0 is an **offline-first, private-LAN, zero-licensing** enterprise accounting, inventory, and India GST/TDS statutory ERP engineered in clean-room Java 25 LTS, Spring Boot 4.1.2 (Jakarta EE 11 Dual-WAR), Dual-Cluster PostgreSQL 18.6, and Angular 22 LTS.

## Architectural Guarantees

1. **Zero Software-Key Licensing & Air-Gapped Default (`GEN-003`, `SEC-015`)**: No license keys, activation servers, seat/company/voucher caps, external font CDNs, or outbound telemetry.
2. **Exact Fixed-Point Arithmetic (`MCJ1-DATA-01`)**: Unconstrained PostgreSQL `numeric` (`trunc(val, 6)`), Java `BigDecimal` (`RoundingMode.UNNECESSARY`), and TypeScript `bigint` scaled arithmetic (`ExactDecimal`).
3. **Dual-Cluster PostgreSQL 18 Trust Split (`FND-DATA-070`)**:
   - **Protected Authority DB (`ma_*`, dev `:55481`)**: Credentials (`Argon2id`), Passkeys (`WebAuthn`), OIDC PKCE links, service keys, wrapped Google Tink DEKs, admin receipts, and `SPRING_SESSION`.
   - **Business Accounting DB (`mc_*`, dev `:55480`)**: Companies, 28 predefined groups, ledgers, 24 voucher types, `DEFERRABLE INITIALLY DEFERRED` double-entry GL (`mc_entry`), MCA `Edit Log`, `MC-AUDIT-2` SHA-256 chain, `AuthorityGuard` single-writer advisory lock, and PostgreSQL `RLS`.
4. **Hardened Non-Root Containers & Dual-WAR Portability (`DEP-002`)**:
   - Runs as non-root (`10001:10001`, `101:101`, `999:999`) with `read_only: true` root filesystems, `cap_drop: [ALL]`, and `internal: true` DB network isolation.
   - Identical `server/target/moneycraft.war` verified on **Apache Tomcat 11.0.x** and **WildFly 42.0.0.Final**.

---

## Quick Start (Local Developer Stack)

### Prerequisites
- **JDK 25 LTS** (`/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home`)
- **Apache Maven 3.10+** (`/opt/homebrew/opt/maven/bin/mvn`)
- **Node.js 24.18+ LTS**
- **Docker Engine 26+ & Docker Compose v2.29+ / v5+**

### 1. Run Hygiene & Compliance Gates
```bash
python3 tools/verify_manifest.py
python3 tools/verify_flyway_backward_compat.py
python3 tools/check_zero_licensing.py
```

### 2. Progressive Stage Builds
```bash
# Gate G1 — Standalone .1800 binary reader only (zero Spring / JPA / network)
python3 tools/build.py --stage reader

# Gate G2-F — Dual-PG18 Foundation, Auth, RBAC & Multi-Company
python3 tools/build.py --stage foundation

# Gate G3 — Full Reactor Build
python3 tools/build.py --stage full

# Gate G4 — Tomcat 11 & WildFly 42 Dual-WAR Qualification
python3 tools/build.py --stage dual-war-qa
```

### 3. Boot Local Hardened Docker Compose Stack
Local dev ports are bound strictly to `127.0.0.1` on non-conflicting high ports (`:18080` HTTP redirect, `:18443` HTTPS gateway, `:55480` Business PG18, `:55481` Authority PG18):
```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev config
docker compose -f docker-compose.yml -f docker-compose.dev.yml --env-file .env.dev up -d --build
```

### 4. Zero-Downtime Release & Instant Rollback (`staging` / `prod`)
```bash
# Mandatory pre-deploy Dual-PG18 causal-cut backup -> Expand Flyway -> Rolling drain -> Smoke check
./deploy/deploy-release.sh --env prod --tag 1.0.0

# Tier-1 < 10s stateless rollback (preserves all vouchers posted during canary window)
./deploy/rollback-release.sh --env prod --to previous
```
