#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.0.0 — Post-Deploy Liveness, Readiness, CSP & Accounting Smoke Probe
# Verifies:
#   1. Internal `:8081/actuator/health/{liveness,readiness}` == UP
#   2. Edge gateway hard-blocks `/actuator/*` with HTTP `404`
#   3. Root `/` serves SPA HTML with per-request CSP nonce (zero `'unsafe-inline'`)
#   4. `/assets/config/runtime-config.json` & `/api/v1/system/status` respond cleanly
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_NAME="dev"
GATEWAY_URL=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --env)
            ENV_NAME="$2"
            shift 2
            ;;
        --gateway-url)
            GATEWAY_URL="$2"
            shift 2
            ;;
        *)
            echo "Usage: smoke-verify.sh --env <dev|staging|prod> [--gateway-url <https://...>]" >&2
            exit 2
            ;;
    esac
done

COMPOSE_OVERLAY="${REPO_ROOT}/docker-compose.${ENV_NAME}.yml"
ENV_FILE="${REPO_ROOT}/.env.${ENV_NAME}"

dc() {
    docker compose \
        -f "${REPO_ROOT}/docker-compose.yml" \
        -f "${COMPOSE_OVERLAY}" \
        --env-file "${ENV_FILE}" \
        "$@"
}

if [[ -z "${GATEWAY_URL}" ]]; then
    case "${ENV_NAME}" in
        dev)
            HTTPS_PORT="$(grep -E '^MC_GATEWAY_HTTPS_PORT=' "${ENV_FILE}" | cut -d= -f2 || echo "18443")"
            GATEWAY_URL="https://127.0.0.1:${HTTPS_PORT:-18443}"
            ;;
        staging)
            HTTPS_PORT="$(grep -E '^MC_STAGING_HTTPS_PORT=' "${ENV_FILE}" | cut -d= -f2 || echo "8443")"
            GATEWAY_URL="https://127.0.0.1:${HTTPS_PORT:-8443}"
            ;;
        prod)
            GATEWAY_URL="https://127.0.0.1:443"
            ;;
    esac
fi

echo "==> [Smoke 1/4] Checking internal moneycraft-api Actuator liveness & readiness (:8081)..."
LIVENESS_JSON="$(dc exec -T moneycraft-api curl -fsS http://127.0.0.1:8081/actuator/health/liveness)"
READINESS_JSON="$(dc exec -T moneycraft-api curl -fsS http://127.0.0.1:8081/actuator/health/readiness)"

if [[ "${LIVENESS_JSON}" != *'"status":"UP"'* ]] || [[ "${READINESS_JSON}" != *'"status":"UP"'* ]]; then
    echo "[FAIL] Actuator probe unhealthy: liveness=${LIVENESS_JSON} readiness=${READINESS_JSON}" >&2
    exit 1
fi
echo "    [OK] Internal :8081 liveness & readiness are UP."

echo "==> [Smoke 2/4] Verifying edge gateway hard-blocks /actuator/health (expect 404)..."
ACTUATOR_CODE="$(curl -sk -o /dev/null -w '%{http_code}' "${GATEWAY_URL}/actuator/health")"
if [[ "${ACTUATOR_CODE}" != "404" ]]; then
    echo "[FAIL] Expected HTTP 404 on ${GATEWAY_URL}/actuator/health, got ${ACTUATOR_CODE}" >&2
    exit 1
fi
echo "    [OK] External /actuator/* blocked with HTTP 404."

echo "==> [Smoke 3/4] Verifying SPA delivery & per-request CSP nonce injection..."
TMP_HDR="$(mktemp)"
TMP_BODY="$(mktemp)"
trap 'rm -f "${TMP_HDR}" "${TMP_BODY}"' EXIT

curl -sk -D "${TMP_HDR}" -o "${TMP_BODY}" "${GATEWAY_URL}/"

if ! grep -qi '^content-security-policy:.*nonce-' "${TMP_HDR}"; then
    echo "[FAIL] Missing per-request nonce in Content-Security-Policy header." >&2
    exit 1
fi
if grep -qi 'unsafe-inline' "${TMP_HDR}"; then
    echo "[FAIL] Forbidden 'unsafe-inline' detected in Content-Security-Policy header." >&2
    exit 1
fi
if grep -q '__MC_CSP_NONCE__' "${TMP_BODY}"; then
    echo "[FAIL] Unreplaced __MC_CSP_NONCE__ placeholder found in index.html." >&2
    exit 1
fi
echo "    [OK] Per-request CSP nonce verified on index.html."

echo "==> [Smoke 4/4] Verifying runtime-config.json & /api/v1/system/status..."
RUNTIME_CFG="$(curl -skf "${GATEWAY_URL}/assets/config/runtime-config.json")"
if [[ "${RUNTIME_CFG}" != *'"apiBaseUrl"'* ]]; then
    echo "[FAIL] Invalid runtime-config.json payload: ${RUNTIME_CFG}" >&2
    exit 1
fi

SYS_STATUS="$(curl -skf "${GATEWAY_URL}/api/v1/system/status")"
if [[ "${SYS_STATUS}" == *'"QUARANTINED"'* ]]; then
    echo "[FAIL] System reported QUARANTINED state after deployment: ${SYS_STATUS}" >&2
    exit 1
fi

echo "[PASS] All post-deployment smoke checks passed against ${GATEWAY_URL}."
