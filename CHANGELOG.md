# Changelog

All notable changes to **MoneyCraft** are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html)
and the Expand-and-Contract zero-downtime migration contract defined in `RELEASE_MANAGEMENT.md`.

## [Unreleased] — `1.0.0-SNAPSHOT` (Phase 1 Bootstrap)

### Added
- **Reactor & Build Governance (`ops(build)`)**: Initialized unified clean-room Maven 3.10 + Java 25 LTS reactor (`reader`, `server`, `qa`) on Spring Boot `4.1.2` with progressive build profiles (`-Preader`, `-Pfoundation`, `-Paccounting`, `-Pimport`, `-Pfull`, `-Pdual-war-qa`).
- **Automated Hygiene & Compliance Gates (`ops(qa)`)**: Added `tools/verify_manifest.py`, `tools/verify_flyway_backward_compat.py`, `tools/check_zero_licensing.py` (`AT-GEN-003`), `tools/build.py`, and Checkstyle rules (`moneycraft-checkstyle.xml`).
- **Hardened Multi-Stage Container Stack (`ops(docker)`)**: Added non-root read-only `Dockerfile`s for `moneycraft-api` (`eclipse-temurin:25-jre-noble`, `uid 10001`, ZGC, `:8081` Actuator probes), `moneycraft-ui` (`nginxinc/nginx-unprivileged:1.28-alpine`, `uid 101`, Brotli/Gzip, `$request_id` CSP nonces, `40-inject-runtime-config.sh`), `moneycraft-gateway` (TLS 1.3, rate limits, unbuffered SSE, `/actuator/*` block), and `moneycraft-pg-backup` (`uid 999` Dual-PG18 synchronized encrypted backup + WAL PITR).
- **Zero-Downtime Release & Dual-WAR Descriptors (`ops(deploy)`)**: Added `docker-compose.{yml,dev.yml,staging.yml,prod.yml}`, `deploy/{pre-deploy-backup-gate.sh,deploy-release.sh,rollback-release.sh,smoke-verify.sh}`, Tomcat 11 / WildFly 42 deployment templates, and GitHub Actions CI/CD promotion workflows.

### Security
- Enforced `internal: true` network isolation (`mc_data_net`) for `moneycraft-business-pg18` and `moneycraft-authority-pg18` (`postgres:18.6-bookworm`), `/run/secrets/*` credential custody, `NOBYPASSRLS` runtime SQL privileges, and zero software-key licensing (`GEN-003` / `SEC-015`).
