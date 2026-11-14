#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.0.0 — One-Command Tier-1 (<10s) & Tier-2 (PITR) Rollback Engine
# Tier 1 (Default): Stateless image-tag revert (`--to previous`) preserving all
#                   double-entry vouchers posted during the `vN` canary window.
# Tier 2 (DR Only): Full synchronized Dual-PG18 restore (`--with-db-restore
#                   --backup-id <ID> --confirm-owner-pitr`).
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_NAME="prod"
TARGET_REF="previous"
WITH_DB_RESTORE="false"
BACKUP_ID=""
OWNER_CONFIRMED="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)
            ENV_NAME="$2"
            shift 2
            ;;
        --to)
            TARGET_REF="$2"
            shift 2
            ;;
        --with-db-restore)
            WITH_DB_RESTORE="true"
            shift 1
            ;;
        --backup-id)
            BACKUP_ID="$2"
            shift 2
            ;;
        --confirm-owner-pitr)
            OWNER_CONFIRMED="true"
            shift 1
            ;;
        *)
            echo "Usage: rollback-release.sh [--env <env>] --to <previous|semver> [--with-db-restore --backup-id <ID> --confirm-owner-pitr]" >&2
            exit 2
            ;;
    esac
done

COMPOSE_OVERLAY="${REPO_ROOT}/docker-compose.${ENV_NAME}.yml"
ENV_FILE="${REPO_ROOT}/.env.${ENV_NAME}"
STATE_DIR="${MC_DEPLOY_STATE_DIR:-${REPO_ROOT}/.deploy-state}"
STATE_FILE="${STATE_DIR}/last-known-good.${ENV_NAME}.env"

if [[ "${TARGET_REF}" == "previous" ]]; then
    if [[ ! -f "${STATE_FILE}" ]]; then
        echo "[FATAL] Rollback state file not found at ${STATE_FILE}" >&2
        exit 1
    fi
    # shellcheck disable=SC1090
    source "${STATE_FILE}"
    ROLLBACK_TAG="${PREVIOUS_IMAGE_TAG}"
    BACKUP_ID="${BACKUP_ID:-${PRE_DEPLOY_BACKUP_ID:-}}"
else
    ROLLBACK_TAG="${TARGET_REF}"
fi

dc_rb() {
    MC_IMAGE_TAG="${ROLLBACK_TAG}" MC_RELEASE_VERSION="${ROLLBACK_TAG}" \
        docker compose \
        -f "${REPO_ROOT}/docker-compose.yml" \
        -f "${COMPOSE_OVERLAY}" \
        --env-file "${ENV_FILE}" \
        "$@"
}

if [[ "${WITH_DB_RESTORE}" == "true" ]]; then
    if [[ "${OWNER_CONFIRMED}" != "true" || -z "${BACKUP_ID}" ]]; then
        echo "[FATAL] Tier-2 Dual-PG18 PITR requires both --backup-id <ID> and --confirm-owner-pitr." >&2
        exit 1
    fi
    echo "==> [Tier-2 PITR] Stopping active writers on moneycraft-api (graceful 25s drain)..."
    dc_rb stop -t 30 moneycraft-gateway moneycraft-api

    echo "==> [Tier-2 PITR] Restoring synchronized Authority & Business PG18 clusters from '${BACKUP_ID}'..."
    dc_rb exec -T moneycraft-pg-backup /opt/moneycraft/backup/restore-pitr.sh --backup-id "${BACKUP_ID}"
fi

echo "==> [Tier-1 Stateless Revert] Rolling moneycraft-api, moneycraft-ui, moneycraft-gateway back to '${ROLLBACK_TAG}'..."
dc_rb up -d --no-deps --wait --wait-timeout 60 moneycraft-api moneycraft-ui moneycraft-gateway

"${SCRIPT_DIR}/smoke-verify.sh" --env "${ENV_NAME}"

sed -i.bak "s/^MC_IMAGE_TAG=.*/MC_IMAGE_TAG=${ROLLBACK_TAG}/" "${ENV_FILE}" && rm -f "${ENV_FILE}.bak"
sed -i.bak "s/^MC_RELEASE_VERSION=.*/MC_RELEASE_VERSION=${ROLLBACK_TAG}/" "${ENV_FILE}" && rm -f "${ENV_FILE}.bak"

echo "[PASS] Rollback to '${ROLLBACK_TAG}' completed cleanly on '${ENV_NAME}'."
