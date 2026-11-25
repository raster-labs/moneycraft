#!/usr/bin/env bash
# ==============================================================================
# MoneyCraft 1.1.0 — Standalone macOS (.dmg, .pkg, .app.zip) Desktop Builder
# Compiles moneycraft-desktop under Java 25 LTS, builds a stripped custom jlink
# runtime image (zero JDBC, zero Spring/JPA, zero server WAR), and packages
# native macOS .dmg, .pkg, and .app.zip bundles via jpackage + hdiutil.
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
ARCH="$(uname -m)"
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
            echo "Usage: build-macos-installer.sh [--app-version <semver>] [--arch <arm64|x64>] [--skip-maven-tests]" >&2
            exit 2
            ;;
    esac
done

if [[ "${ARCH}" == "aarch64" ]]; then
    ARCH="arm64"
elif [[ "${ARCH}" == "x86_64" ]]; then
    ARCH="x64"
fi

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

JPACKAGE_VERSION="${APP_VERSION%%-*}"
COMMON_ARGS=(
    --name "MoneyCraft"
    --app-version "${JPACKAGE_VERSION}"
    --vendor "Raster Images"
    --description "MoneyCraft Personal Accounting Desktop Client"
    --input "${JPACKAGE_INPUT_DIR}"
    --main-jar "moneycraft-desktop.jar"
    --main-class "com.raster.moneycraft.desktop.MoneyCraftDesktopMain"
    --runtime-image "${RUNTIME_IMAGE_DIR}"
    --dest "${INSTALLER_DIR}"
    --java-options "-Dapple.laf.useScreenMenuBar=true"
)

CANONICAL_APP_ZIP="${INSTALLER_DIR}/MoneyCraft-Desktop-${APP_VERSION}-macos-${ARCH}-app.zip"
CANONICAL_DMG="${INSTALLER_DIR}/MoneyCraft-Desktop-${APP_VERSION}-macos-${ARCH}.dmg"
CANONICAL_PKG="${INSTALLER_DIR}/MoneyCraft-Desktop-${APP_VERSION}-macos-${ARCH}.pkg"

echo "[3/5] Packaging macOS .app bundle (.app.zip) & deterministic UDZO .dmg image..."
"${JPACKAGE_BIN}" --type app-image "${COMMON_ARGS[@]}"
(
    cd "${INSTALLER_DIR}"
    ditto -c -k --sequesterRsrc --keepParent "MoneyCraft.app" "$(basename "${CANONICAL_APP_ZIP}")"
    STAGE_DMG_DIR="$(mktemp -d)"
    cp -R "MoneyCraft.app" "${STAGE_DMG_DIR}/MoneyCraft.app"
    ln -s /Applications "${STAGE_DMG_DIR}/Applications"
    hdiutil create -volname "MoneyCraft ${APP_VERSION}" -srcfolder "${STAGE_DMG_DIR}" -ov -format UDZO "${CANONICAL_DMG}" >/dev/null
    rm -rf "${STAGE_DMG_DIR}" "MoneyCraft.app"
)

echo "[4/5] Packaging macOS .pkg installer via jpackage..."
"${JPACKAGE_BIN}" --type pkg "${COMMON_ARGS[@]}"
GENERATED_PKG="$(find "${INSTALLER_DIR}" -maxdepth 1 -name "*.pkg" | head -n 1)"
mv -f "${GENERATED_PKG}" "${CANONICAL_PKG}"

echo "[5/5] Writing SHA256SUMS-macos.txt..."
(
    cd "${INSTALLER_DIR}"
    shasum -a 256 \
        "$(basename "${CANONICAL_DMG}")" \
        "$(basename "${CANONICAL_PKG}")" \
        "$(basename "${CANONICAL_APP_ZIP}")" \
        > SHA256SUMS-macos.txt
    cp -f SHA256SUMS-macos.txt SHA256SUMS.txt
    cat SHA256SUMS-macos.txt
)
