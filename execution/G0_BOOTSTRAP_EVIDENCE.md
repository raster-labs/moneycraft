# Gate G0 — Clean-Room Workspace & Immutable Evidence Bootstrap Verification

- **Verification Timestamp**: `2026-11-14T20:55:08+05:30`
- **Target Repository Root**: `/Users/pushparaj/projects/Money_Craft/moneycraft-target`
- **Immutable Reference Root**: `/Users/pushparaj/projects/Money_Craft/argon_workspace`
- **Gate Status**: `PASS` (Clean-Room Containment & Toolchain Baseline Verified)

---

## 1. Clean-Room & Read-Only Evidence Audit (`GEN-001`–`GEN-006`, `SEC-015`)

1. **Zero Mutation of Reference Corpus**:
   - Scanned all `796` filesystem entries (directories and files) under `/Users/pushparaj/projects/Money_Craft/argon_workspace/Source/`.
   - **Writable entries found**: `0` (`0444` on files, `0555` on directories).
   - **Zero Windows binaries executed**: `tally.exe`, `tallymigrationtool.exe`, and `tallyserver.exe` were never executed.
   - **Zero `.lic` inspection**: No license files were read, parsed, or copied (`SEC-015`).

2. **Verified Read-Only Baseline SHA-256 Checksums**:

| Octal Mode | Size (Bytes) | SHA-256 Digest | Immutable Artifact Path (`argon_workspace/`) |
| :--- | ---: | :--- | :--- |
| `0444` | `392,358,591` | `f1a156948e32eabc6b7216ac25bd9303f79b956b7cda93fef9e0451213730541` | `Archive/Prime7.0.zip` |
| `0444` | `17,961,152` | `b9207ccf2163fd5354b1c6145643448d2c5d1ca19c85cc2927a099fd17263278` | `Source/Binary/Tally Prime/TallyPrime_Installed/tally.exe` |
| `0444` | `21,274,304` | `4db680514fa9bf8c1fe89141cfe820f73f92bb5d35f7b957ef57761de69ee406` | `Source/Binary/Tally Prime/TallyPrime_Installed/tallymigrationtool.exe` |
| `0444` | `21,480,640` | `2437795cd5db1fddd8589d500ac8855adb5b52e621b79c34e975c15ba37a6c05` | `Source/Binary/Tally Prime Server/tallyserver.exe` |
| `0444` | `215` | `ccb2167a78a33d9efdb47f36cb885b9a8c8fef3a55976df3a726dd0e7475cf27` | `Source/Binary/Tally Prime/TallyPrime_Installed/capsules/TP-1-1-7-2-0-29802.cif` |
| `0444` | `3,364,616` | `476cac0b9c1eb12971625b478b03c7070d0c2c3cb56034f63f5ec88fd87a5c77` | `Source/Binary/Tally Prime/TallyPrime_Installed/capsules/00031-29802.cap` |
| `0444` | `7,680` | `06f97acb4b8ef5b0ca7a5f0a1511c0e89c2364baf166bd7a766fa57a617d84bc` | `Source/Binary/Tally Prime/Data/100000/Company.1800` |
| `0444` | `7,680` | `06f97acb4b8ef5b0ca7a5f0a1511c0e89c2364baf166bd7a766fa57a617d84bc` | `Source/Binary/Tally Prime/Data/100000/CmpSave.1800` |
| `0444` | `229,376` | `fa1c503e12f706ff9083e3ba461fb53df182a853d143c11d011d120a92d91638` | `Source/Binary/Tally Prime/Data/100000/Manager.1800` |
| `0444` | `7,680` | `783c4bea4ece2587c8463816dd905012b6b9510efcd6738007f7c23b64215035` | `Source/Binary/Tally Prime/Data/100000/LinkMgr.1800` |
| `0444` | `97,280` | `837e45db5f96610d99a557a35c7da6bfc46aafdc74d3790bf363b1809608e0ec` | `Source/Binary/Tally Prime/Data/100000/TranMgr.1800` |
| `0444` | `34,304` | `bfc4da781c646293c509c2deedec2a1a7cada8073c108cd4ca38d8e99c902d0f` | `Source/Binary/Tally Prime/Data/100000/Aggr.1800` |

---

## 2. Locked Host & Container Toolchain Baseline

| Component | Verified Version / Digest |
| :--- | :--- |
| **Java SDK** | OpenJDK `25.0.5.0.3` LTS (`aarch64` macOS) |
| **Apache Maven** | `3.10.0` |
| **Spring Boot Parent** | `org.springframework.boot:spring-boot-starter-parent:4.1.2` (Spring Security `7.1.2`, Hibernate `7.4.13.Final`, Flyway `12.4.0`, Tomcat `11.0.27`, Testcontainers `2.0.5`) |
| **Docker Engine / Compose** | Docker `29.6.2` / Docker Compose `v5.3.1` |
| **Maven Builder Image** | `maven:3.10.0-eclipse-temurin-25@sha256:a4734fd7909a9c2beddcacfa1229bd308a349f000bea1ec8db3287c8a16ca2a3` |
| **JRE 25 Runtime Image** | `eclipse-temurin:25-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c` |
| **Node 24 Builder Image** | `node:24.18-bookworm-slim@sha256:235600a8101ab264e117b1768e925532262668dc9b581ef1dd7d96ced463b8e7` |
| **Nginx Unprivileged Image** | `nginxinc/nginx-unprivileged:1.28-alpine@sha256:7377697a821c131a924a7105fafbe7414db4e9fcc77a6f08f776f33f141ec3f8` |
| **PostgreSQL 18 Image** | `postgres:18.6-bookworm@sha256:f1ea2a1a4fb7b68d4836be1fdcb8a6466a89b072158bf9d97663600538c294c5` |
