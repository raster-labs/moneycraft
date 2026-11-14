#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.0.0 — Mandatory Pre-Deployment Dual-PG18 Snapshot Gate
# Enforces `RELEASE_MANAGEMENT.md` Section 3.2 before `moneycraft-migrator`:
#   1. Verifies backup volume headroom >= 2.0x combined PG18 cluster sizes
#   2. Verifies pre-upgrade receipt/audit health across Authority & Business DBs
#   3. Takes an encrypted synchronized snapshot + WAL switch (`backup-manifest.json`)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_NAME="prod"
TARGET_TAG="unspecified"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)
            ENV_NAME="$2"
            shift 2
            ;;
        --tag)
            TARGET_TAG="$2"
            shift 2
            ;;
        *)
            echo "Usage: pre-deploy-backup-gate.sh --env <dev|staging|prod> --tag <semver>" >&2
            exit 2
            ;;
    esac
done

COMPOSE_OVERLAY="${REPO_ROOT}/docker-compose.${ENV_NAME}.yml"
ENV_FILE="${REPO_ROOT}/.env.${ENV_NAME}"

if [[ ! -f "${COMPOSE_OVERLAY}" || ! -f "${ENV_FILE}" ]]; then
    echo "[FATAL] Missing ${COMPOSE_OVERLAY} or ${ENV_FILE}" >&2
    exit 1
fi

dc() {
    docker compose \
        -f "${REPO_ROOT}/docker-compose.yml" \
        -f "${COMPOSE_OVERLAY}" \
        --env-file "${ENV_FILE}" \
        "$@"
}

echo "==> [Gate 1/3] Checking Dual-PG18 backup disk headroom (>= 2.0x DB size)..."
COMBINED_DB_BYTES="$(dc exec -T moneycraft-pg-backup bash -lc '
    export PGPASSWORD="$(tr -d "\r\n" < "${MC_DB_BACKUP_PASSWORD_FILE}")"
    AUTH_SZ=$(psql -h "${MC_AUTHORITY_DB_HOST}" -p "${MC_AUTHORITY_DB_PORT}" -U "${MC_BACKUP_DB_USER}" -d "${MC_AUTHORITY_DB_NAME}" -Atqc "SELECT pg_database_size(current_database());")
    BIZ_SZ=$(psql -h "${MC_BUSINESS_DB_HOST}" -p "${MC_BUSINESS_DB_PORT}" -U "${MC_BACKUP_DB_USER}" -d "${MC_BUSINESS_DB_NAME}" -Atqc "SELECT pg_database_size(current_database());")
    echo $(( AUTH_SZ + BIZ_SZ ))
')"
FREE_BACKUP_BYTES="$(dc exec -T moneycraft-pg-backup bash -lc 'df -B1 /backups | awk "NR==2 {print \$4}"')"
REQUIRED_BYTES=$(( COMBINED_DB_BYTES * 2 ))

if (( FREE_BACKUP_BYTES < REQUIRED_BYTES )); then
    echo "[FATAL] Insufficient /backups free space: free=${FREE_BACKUP_BYTES}B required=${REQUIRED_BYTES}B" >&2
    exit 1
fi
echo "    [OK] Disk headroom verified (free=${FREE_BACKUP_BYTES}B >= required=${REQUIRED_BYTES}B)."

echo "==> [Gate 2/3] Verifying pre-upgrade Authority <-> Business receipt parity..."
dc exec -T moneycraft-pg-backup bash -lc '
    export PGPASSWORD="$(tr -d "\r\n" < "${MC_DB_BACKUP_PASSWORD_FILE}")"
    AUTH_HAS=$(psql -h "${MC_AUTHORITY_DB_HOST}" -p "${MC_AUTHORITY_DB_PORT}" -U "${MC_BACKUP_DB_USER}" -d "${MC_AUTHORITY_DB_NAME}" -Atqc "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='\''public'\'' AND table_name='\''ma_receipt'\'';")
    BIZ_HAS=$(psql -h "${MC_BUSINESS_DB_HOST}" -p "${MC_BUSINESS_DB_PORT}" -U "${MC_BACKUP_DB_USER}" -d "${MC_BUSINESS_DB_NAME}" -Atqc "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='\''public'\'' AND table_name='\''mc_admin_receipt_mirror'\'';")
    if [[ "${AUTH_HAS}" == "1" && "${BIZ_HAS}" == "1" ]]; then
        A_SEQ=$(psql -h "${MC_AUTHORITY_DB_HOST}" -p "${MC_AUTHORITY_DB_PORT}" -U "${MC_BACKUP_DB_USER}" -d "${MC_AUTHORITY_DB_NAME}" -Atqc "SELECT COALESCE(MAX(receipt_seq), 0) FROM ma_receipt;")
        B_SEQ=$(psql -h "${MC_BUSINESS_DB_HOST}" -p "${MC_BUSINESS_DB_PORT}" -U "${MC_BACKUP_DB_USER}" -d "${MC_BUSINESS_DB_NAME}" -Atqc "SELECT COALESCE(MAX(receipt_seq), 0) FROM mc_admin_receipt_mirror;")
        if [[ "${A_SEQ}" != "${B_SEQ}" ]]; then
            echo "[FATAL] Pre-deploy receipt divergence detected: ma_receipt=${A_SEQ} vs mc_admin_receipt_mirror=${B_SEQ}" >&2
            exit 1
        fi
    fi
'
echo "    [OK] Pre-upgrade Dual-PG18 causal state is healthy."

UTC_TS="$(date -u +%Y%m%dT%H%M%SZ)"
SANITIZED_TAG="$(printf '%s' "${TARGET_TAG}" | tr -c 'A-Za-z0-9._-' '-')"
BACKUP_ID="pre-deploy-${SANITIZED_TAG}-${UTC_TS}"

echo "==> [Gate 3/3] Taking encrypted Dual-PG18 snapshot '${BACKUP_ID}'..."
dc exec -T moneycraft-pg-backup /opt/moneycraft/backup/backup-and-wal-archive.sh \
    --mode full-snapshot \
    --output-id "${BACKUP_ID}"

dc exec -T moneycraft-pg-backup bash -lc "sha256sum -c /backups/${BACKUP_ID}/backup-manifest.json.sha256"

echo "PRE_DEPLOY_BACKUP_ID=${BACKUP_ID}"
echo "[PASS] Pre-deployment backup gate succeeded (${BACKUP_ID})."
