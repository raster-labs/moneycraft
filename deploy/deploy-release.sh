#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.0.0 — Zero-Downtime Release Orchestrator (`RELEASE_MANAGEMENT.md`)
# Sequence:
#   1. Expand-and-Contract Flyway static DDL compatibility check
#   2. Mandatory pre-deploy Dual-PG18 encrypted snapshot gate
#   3. Record `last-known-good.<env>.env` for < 10s Tier-1 rollback
#   4. Execute isolated `moneycraft-migrator` (`mc_flyway_migrator`)
#   5. Rolling update of `moneycraft-api`, `moneycraft-ui`, `moneycraft-gateway`
#   6. Post-cutover `smoke-verify.sh` (with optional `--auto-rollback`)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_NAME="prod"
TARGET_TAG=""
AUTO_ROLLBACK="false"

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
        --auto-rollback)
            AUTO_ROLLBACK="true"
            shift 1
            ;;
        *)
            echo "Usage: deploy-release.sh --env <dev|staging|prod> --tag <semver> [--auto-rollback]" >&2
            exit 2
            ;;
    esac
done

if [[ -z "${TARGET_TAG}" ]]; then
    echo "[FATAL] --tag <semver> is required." >&2
    exit 2
fi

COMPOSE_OVERLAY="${REPO_ROOT}/docker-compose.${ENV_NAME}.yml"
ENV_FILE="${REPO_ROOT}/.env.${ENV_NAME}"
STATE_DIR="${MC_DEPLOY_STATE_DIR:-${REPO_ROOT}/.deploy-state}"
STATE_FILE="${STATE_DIR}/last-known-good.${ENV_NAME}.env"

mkdir -p "${STATE_DIR}"

if [[ -f "${REPO_ROOT}/tools/verify_flyway_backward_compat.py" ]]; then
    echo "==> [Step 1/5] Verifying Expand-and-Contract Flyway backward compatibility..."
    python3 "${REPO_ROOT}/tools/verify_flyway_backward_compat.py"
fi

echo "==> [Step 2/5] Running mandatory pre-deployment Dual-PG18 backup gate..."
GATE_OUT="$("${SCRIPT_DIR}/pre-deploy-backup-gate.sh" --env "${ENV_NAME}" --tag "${TARGET_TAG}")"
echo "${GATE_OUT}"
BACKUP_ID="$(printf '%s\n' "${GATE_OUT}" | awk -F= '/^PRE_DEPLOY_BACKUP_ID=/ {print $2}' | tail -n1)"

PREV_TAG="$(grep -E '^MC_IMAGE_TAG=' "${ENV_FILE}" | cut -d= -f2 || echo "unknown")"
cat > "${STATE_FILE}" <<EOF
PREVIOUS_IMAGE_TAG=${PREV_TAG}
CANDIDATE_IMAGE_TAG=${TARGET_TAG}
PRE_DEPLOY_BACKUP_ID=${BACKUP_ID}
RECORDED_AT_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
chmod 0600 "${STATE_FILE}"

dc_target() {
    MC_IMAGE_TAG="${TARGET_TAG}" MC_RELEASE_VERSION="${TARGET_TAG}" \
        docker compose \
        -f "${REPO_ROOT}/docker-compose.yml" \
        -f "${COMPOSE_OVERLAY}" \
        --env-file "${ENV_FILE}" \
        "$@"
}

echo "==> [Step 3/5] Applying Expand-only Flyway migrations via moneycraft-migrator..."
dc_target run --rm moneycraft-migrator

echo "==> [Step 4/5] Performing graceful rolling update to tag '${TARGET_TAG}'..."
dc_target up -d --no-deps --wait --wait-timeout 90 moneycraft-api moneycraft-ui moneycraft-gateway

echo "==> [Step 5/5] Executing post-cutover smoke verification..."
if ! "${SCRIPT_DIR}/smoke-verify.sh" --env "${ENV_NAME}"; then
    echo "[ERROR] Post-deploy smoke verification failed for tag '${TARGET_TAG}'!" >&2
    if [[ "${AUTO_ROLLBACK}" == "true" ]]; then
        echo "==> [AUTO-ROLLBACK] Reverting stateless services to '${PREV_TAG}'..." >&2
        "${SCRIPT_DIR}/rollback-release.sh" --env "${ENV_NAME}" --to previous
    fi
    exit 1
fi

sed -i.bak "s/^MC_IMAGE_TAG=.*/MC_IMAGE_TAG=${TARGET_TAG}/" "${ENV_FILE}" && rm -f "${ENV_FILE}.bak"
sed -i.bak "s/^MC_RELEASE_VERSION=.*/MC_RELEASE_VERSION=${TARGET_TAG}/" "${ENV_FILE}" && rm -f "${ENV_FILE}.bak"

echo "[PASS] Zero-downtime release '${TARGET_TAG}' is live and verified on '${ENV_NAME}'."
