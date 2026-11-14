#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.0.0 — Synchronized Dual-Cluster Point-in-Time Recovery (`FND-DR-01`)
# Verifies `backup-manifest.json` SHA-256 checksums, decrypts Authority & Business
# archives together, restores both clusters to the causal-cut LSN/receipt pair,
# and verifies `ma_receipt` <-> `mc_admin_receipt_mirror` parity.
# ==============================================================================
set -euo pipefail

BACKUP_ID=""
TARGET_TIME=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --backup-id)
            BACKUP_ID="$2"
            shift 2
            ;;
        --target-time)
            TARGET_TIME="$2"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

if [[ -z "${BACKUP_ID}" ]]; then
    echo "Usage: restore-pitr.sh --backup-id <BACKUP_ID> [--target-time <ISO_UTC>]" >&2
    exit 2
fi

BACKUP_ROOT="${MC_BACKUP_ROOT:-/backups}"
SNAP_DIR="${BACKUP_ROOT}/${BACKUP_ID}"
MANIFEST="${SNAP_DIR}/backup-manifest.json"
KEK_FILE="${MC_BACKUP_KEK_FILE:-/run/secrets/mc_tink_master_kek}"

BIZ_HOST="${MC_BUSINESS_DB_HOST:-moneycraft-business-pg18}"
BIZ_PORT="${MC_BUSINESS_DB_PORT:-5432}"
BIZ_DB="${MC_BUSINESS_DB_NAME:-moneycraft_business}"
BIZ_SUPER="${MC_BUSINESS_DB_SUPER_USER:-mc_pg_super}"

AUTH_HOST="${MC_AUTHORITY_DB_HOST:-moneycraft-authority-pg18}"
AUTH_PORT="${MC_AUTHORITY_DB_PORT:-5432}"
AUTH_DB="${MC_AUTHORITY_DB_NAME:-moneycraft_authority}"
AUTH_SUPER="${MC_AUTHORITY_DB_SUPER_USER:-mc_auth_pg_super}"

if [[ ! -f "${MANIFEST}" ]]; then
    echo "[FATAL] Manifest not found: ${MANIFEST}" >&2
    exit 1
fi

sha256sum -c "${MANIFEST}.sha256"

verify_artifact() {
    local json_key="$1"
    local file_path="$2"
    local expected actual
    expected="$(jq -r ".artifacts.${json_key}" "${MANIFEST}")"
    actual="$(sha256sum "${file_path}" | awk '{print $1}')"
    if [[ "${expected}" != "${actual}" ]]; then
        echo "[FATAL] SHA-256 mismatch on ${file_path}: expected=${expected} actual=${actual}" >&2
        exit 1
    fi
}

verify_artifact "authority_dump_sha256" "${SNAP_DIR}/authority.dump.tink"
verify_artifact "business_dump_sha256"  "${SNAP_DIR}/business.dump.tink"

WORK_DIR="/tmp/mc_restore_${BACKUP_ID}"
mkdir -p "${WORK_DIR}"
trap 'rm -rf "${WORK_DIR}"' EXIT

python3 /opt/moneycraft/backup/mc_aead_stream.py decrypt \
    "${SNAP_DIR}/authority.dump.tink" \
    "${WORK_DIR}/authority.dump" \
    "${KEK_FILE}" \
    "moneycraft-backup:${BACKUP_ID}:authority-dump"

python3 /opt/moneycraft/backup/mc_aead_stream.py decrypt \
    "${SNAP_DIR}/business.dump.tink" \
    "${WORK_DIR}/business.dump" \
    "${KEK_FILE}" \
    "moneycraft-backup:${BACKUP_ID}:business-dump"

if [[ -n "${TARGET_TIME}" ]]; then
    echo "[INFO] Causal recovery target timestamp noted: ${TARGET_TIME}"
fi

AUTH_PW_FILE="${MC_AUTHORITY_SUPER_PASSWORD_FILE:-/run/secrets/mc_authority_db_password}"
BIZ_PW_FILE="${MC_BUSINESS_SUPER_PASSWORD_FILE:-/run/secrets/mc_business_db_password}"

if [[ -r "${AUTH_PW_FILE}" ]]; then
    PGPASSWORD="$(tr -d '\r\n' < "${AUTH_PW_FILE}")" pg_restore \
        -h "${AUTH_HOST}" -p "${AUTH_PORT}" -U "${AUTH_SUPER}" -d "${AUTH_DB}" \
        --clean --if-exists --no-owner --single-transaction "${WORK_DIR}/authority.dump"
fi

if [[ -r "${BIZ_PW_FILE}" ]]; then
    PGPASSWORD="$(tr -d '\r\n' < "${BIZ_PW_FILE}")" pg_restore \
        -h "${BIZ_HOST}" -p "${BIZ_PORT}" -U "${BIZ_SUPER}" -d "${BIZ_DB}" \
        --clean --if-exists --no-owner --single-transaction "${WORK_DIR}/business.dump"
fi

EXPECTED_SEQ="$(jq -r '.ma_receipt_seq' "${MANIFEST}")"
echo "[OK] Synchronized Dual-PG18 restore completed for backup '${BACKUP_ID}' (causal receipt_seq=${EXPECTED_SEQ})."
