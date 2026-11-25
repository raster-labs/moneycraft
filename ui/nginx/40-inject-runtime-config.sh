#!/bin/sh
# ==============================================================================
# MoneyCraft 1.0.0 — "Build Once, Promote Everywhere" Runtime Config Injector
# Executed automatically by `/docker-entrypoint.sh` before Nginx workers start.
# Writes `/usr/share/nginx/html/assets/config/runtime-config.json` on a dedicated
# writable `tmpfs` mount so the container root filesystem remains `read_only: true`.
# Supports either `MC_PUBLIC_API_URL` directly or `MC_BACKEND_API_HOST` + `MC_BACKEND_API_PORT`.
# ==============================================================================
set -eu

TARGET_DIR="/usr/share/nginx/html/assets/config"
TARGET_FILE="${TARGET_DIR}/runtime-config.json"

mkdir -p "${TARGET_DIR}"

escape_json() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

BACKEND_SCHEME="$(escape_json "${MC_BACKEND_API_SCHEME:-https}")"
BACKEND_HOST="$(escape_json "${MC_BACKEND_API_HOST:-}")"
BACKEND_PORT="$(escape_json "${MC_BACKEND_API_PORT:-}")"

RAW_API_URL="${MC_PUBLIC_API_URL:-/api/v1}"
if [ "${RAW_API_URL}" = "/api/v1" ] && [ -n "${BACKEND_HOST}" ] && [ -n "${BACKEND_PORT}" ]; then
    RAW_API_URL="${BACKEND_SCHEME}://${BACKEND_HOST}:${BACKEND_PORT}/api/v1"
fi

API_URL="$(escape_json "${RAW_API_URL}")"
ENV_LABEL="$(escape_json "${MC_ENV_LABEL:-PRODUCTION}")"
DEFAULT_CCY="$(escape_json "${MC_DEFAULT_CURRENCY:-INR}")"
REL_VER="$(escape_json "${MC_RELEASE_VERSION:-1.0.0}")"

cat > "${TARGET_FILE}.tmp" <<EOF
{
  "apiBaseUrl": "${API_URL}",
  "envLabel": "${ENV_LABEL}",
  "defaultCurrency": "${DEFAULT_CCY}",
  "releaseVersion": "${REL_VER}",
  "backendScheme": "${BACKEND_SCHEME}",
  "backendHost": "${BACKEND_HOST}",
  "backendPort": "${BACKEND_PORT}"
}
EOF

chmod 0644 "${TARGET_FILE}.tmp"
mv -f "${TARGET_FILE}.tmp" "${TARGET_FILE}"
