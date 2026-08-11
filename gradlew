#!/bin/sh
set -eu

# PKap Gradle bootstrapper.
# The repository currently does not vendor gradle-wrapper.jar, so this script
# uses an installed Gradle 8.10.2 when available and otherwise downloads the
# official binary distribution into GRADLE_USER_HOME after SHA-256 validation.

GRADLE_VERSION="8.10.2"
GRADLE_SHA256="31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26"
DIST_NAME="gradle-${GRADLE_VERSION}-bin.zip"
DIST_URL="https://services.gradle.org/distributions/${DIST_NAME}"

HOME_DIR="${HOME:-.}"
CACHE_ROOT="${GRADLE_USER_HOME:-${HOME_DIR}/.gradle}/pkap-bootstrap"
GRADLE_HOME="${CACHE_ROOT}/gradle-${GRADLE_VERSION}"
GRADLE_BIN="${GRADLE_HOME}/bin/gradle"
ZIP_FILE="${CACHE_ROOT}/${DIST_NAME}"

if [ -x "$GRADLE_BIN" ]; then
    exec "$GRADLE_BIN" "$@"
fi

if command -v gradle >/dev/null 2>&1; then
    INSTALLED_VERSION="$(gradle --version 2>/dev/null | sed -n 's/^Gradle //p' | head -n 1 || true)"
    if [ "$INSTALLED_VERSION" = "$GRADLE_VERSION" ]; then
        exec gradle "$@"
    fi
fi

if ! command -v java >/dev/null 2>&1; then
    echo "PKap build: Java 17 is required but 'java' was not found in PATH." >&2
    exit 1
fi

mkdir -p "$CACHE_ROOT"

if [ ! -f "$ZIP_FILE" ]; then
    TMP_FILE="${ZIP_FILE}.tmp.$$"
    trap 'rm -f "$TMP_FILE"' EXIT HUP INT TERM
    echo "PKap build: downloading Gradle ${GRADLE_VERSION}..." >&2
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --connect-timeout 20 -o "$TMP_FILE" "$DIST_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$TMP_FILE" "$DIST_URL"
    else
        echo "PKap build: install curl or wget to bootstrap Gradle." >&2
        exit 1
    fi
    mv "$TMP_FILE" "$ZIP_FILE"
    trap - EXIT HUP INT TERM
fi

if command -v sha256sum >/dev/null 2>&1; then
    printf '%s  %s\n' "$GRADLE_SHA256" "$ZIP_FILE" | sha256sum -c - >/dev/null
elif command -v shasum >/dev/null 2>&1; then
    printf '%s  %s\n' "$GRADLE_SHA256" "$ZIP_FILE" | shasum -a 256 -c - >/dev/null
else
    echo "PKap build: sha256sum or shasum is required to verify Gradle." >&2
    exit 1
fi

if ! command -v unzip >/dev/null 2>&1; then
    echo "PKap build: unzip is required to unpack Gradle." >&2
    exit 1
fi

if [ ! -x "$GRADLE_BIN" ]; then
    echo "PKap build: unpacking Gradle ${GRADLE_VERSION}..." >&2
    unzip -q -o "$ZIP_FILE" -d "$CACHE_ROOT"
fi

if [ ! -x "$GRADLE_BIN" ]; then
    echo "PKap build: Gradle bootstrap failed." >&2
    exit 1
fi

exec "$GRADLE_BIN" "$@"
