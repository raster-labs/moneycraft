#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.1.0 — Standalone Linux (.deb, .rpm, .tar.gz) Desktop Builder
# Compiles moneycraft-desktop under Java 25 LTS, builds a stripped custom jlink
# runtime image (zero JDBC, zero Spring/JPA, zero server WAR), and packages
# native Linux .deb, .rpm (if rpmbuild is present), and portable .tar.gz bundles.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DESKTOP_DIR="${REPO_ROOT}/desktop"
TARGET_DIR="${DESKTOP_DIR}/target"
JPACKAGE_INPUT_DIR="${TARGET_DIR}/jpackage-input"
RUNTIME_IMAGE_DIR="${TARGET_DIR}/runtime-image"
INSTALLER_DIR="${TARGET_DIR}/installer"

APP_VERSION="1.1.0"
ARCH="x64"
SKIP_MAVEN_TESTS="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --app-version)
            APP_VERSION="$2"
            shift 2
            ;;
        --arch)
            ARCH="$2"
            shift 2
            ;;
        --skip-maven-tests)
            SKIP_MAVEN_TESTS="true"
            shift 1
            ;;
        *)
            echo "Usage: build-linux-installer.sh [--app-version <semver>] [--arch <x64|arm64>] [--skip-maven-tests]" >&2
            exit 2
            ;;
    esac
done

if [[ -z "${JAVA_HOME:-}" ]]; then
    echo "[FATAL] JAVA_HOME must point to a Java 25 LTS JDK installation." >&2
    exit 1
fi

JLINK_BIN="${JAVA_HOME}/bin/jlink"
JPACKAGE_BIN="${JAVA_HOME}/bin/jpackage"

if [[ ! -x "${JLINK_BIN}" ]] || [[ ! -x "${JPACKAGE_BIN}" ]]; then
    echo "[FATAL] jlink or jpackage binary not found under JAVA_HOME=${JAVA_HOME}" >&2
    exit 1
fi

cd "${REPO_ROOT}"

MVN_ARGS=(-B clean package -pl desktop)
if [[ "${SKIP_MAVEN_TESTS}" == "true" ]]; then
    MVN_ARGS+=(-DskipTests)
fi

echo "[1/5] Building moneycraft-desktop standalone JAR..."
mvn "${MVN_ARGS[@]}"

rm -rf "${RUNTIME_IMAGE_DIR}" "${INSTALLER_DIR}"
mkdir -p "${INSTALLER_DIR}"

MODULES="java.base,java.desktop,java.net.http,jdk.httpserver,java.naming,java.security.jgss,jdk.crypto.ec"
echo "[2/5] Creating stripped Java 25 LTS jlink runtime image (${MODULES})..."
"${JLINK_BIN}" \
    --add-modules "${MODULES}" \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --output "${RUNTIME_IMAGE_DIR}"

COMMON_ARGS=(
    --name "MoneyCraft"
    --app-version "${APP_VERSION}"
    --vendor "Raster Images"
    --description "MoneyCraft Personal Accounting Desktop Client"
    --input "${JPACKAGE_INPUT_DIR}"
    --main-jar "moneycraft-desktop.jar"
    --main-class "com.raster.moneycraft.desktop.MoneyCraftDesktopMain"
    --runtime-image "${RUNTIME_IMAGE_DIR}"
    --dest "${INSTALLER_DIR}"
)

echo "[3/5] Packaging portable Linux app-image (.tar.gz) via jpackage..."
"${JPACKAGE_BIN}" --type app-image "${COMMON_ARGS[@]}"
CANONICAL_TAR_GZ="${INSTALLER_DIR}/MoneyCraft-Desktop-${APP_VERSION}-linux-${ARCH}.tar.gz"
(
    cd "${INSTALLER_DIR}"
    tar -czf "$(basename "${CANONICAL_TAR_GZ}")" "MoneyCraft"
    rm -rf "MoneyCraft"
)

echo "[4/5] Packaging Linux .deb (and .rpm if rpmbuild available) via jpackage..."
"${JPACKAGE_BIN}" --type deb \
    "${COMMON_ARGS[@]}" \
    --linux-shortcut \
    --linux-menu-group "Office;Finance;" \
    --linux-package-name "moneycraft-desktop"

GENERATED_DEB="$(find "${INSTALLER_DIR}" -maxdepth 1 -name "*.deb" | head -n 1)"
CANONICAL_DEB="${INSTALLER_DIR}/MoneyCraft-Desktop-${APP_VERSION}-linux-${ARCH}.deb"
mv -f "${GENERATED_DEB}" "${CANONICAL_DEB}"

HASH_TARGETS=("$(basename "${CANONICAL_DEB}")" "$(basename "${CANONICAL_TAR_GZ}")")

if command -v rpmbuild >/dev/null 2>&1; then
    "${JPACKAGE_BIN}" --type rpm \
        "${COMMON_ARGS[@]}" \
        --linux-shortcut \
        --linux-menu-group "Office;Finance;" \
        --linux-package-name "moneycraft-desktop"
    GENERATED_RPM="$(find "${INSTALLER_DIR}" -maxdepth 1 -name "*.rpm" | head -n 1)"
    CANONICAL_RPM="${INSTALLER_DIR}/MoneyCraft-Desktop-${APP_VERSION}-linux-${ARCH}.rpm"
    mv -f "${GENERATED_RPM}" "${CANONICAL_RPM}"
    HASH_TARGETS+=("$(basename "${CANONICAL_RPM}")")
fi

echo "[5/5] Writing SHA256SUMS-linux.txt..."
(
    cd "${INSTALLER_DIR}"
    sha256sum "${HASH_TARGETS[@]}" > SHA256SUMS-linux.txt
    cp -f SHA256SUMS-linux.txt SHA256SUMS.txt
    cat SHA256SUMS-linux.txt
)
