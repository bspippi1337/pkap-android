#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
  JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java
  exec "$JAVA_CMD" -Xmx64m -Xms64m -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

if [ ! -f "$PROPS" ]; then
  echo "PKap: missing $PROPS" >&2
  exit 1
fi

URL=$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed 's/\\:/:/g')
[ -n "$URL" ] || { echo "PKap: distributionUrl missing" >&2; exit 1; }

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/pkap-bootstrap"
ZIP="$CACHE/gradle.zip"
DIST="$CACHE/dist"
mkdir -p "$CACHE" "$DIST"

if [ ! -x "$DIST/bin/gradle" ]; then
  echo "PKap // BLCKSWAN: bootstrapping Gradle from $URL" >&2
  rm -f "$ZIP"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 -o "$ZIP" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "PKap: install curl or wget" >&2
    exit 1
  fi

  rm -rf "$CACHE/unpack"
  mkdir -p "$CACHE/unpack"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP" -d "$CACHE/unpack"
  else
    echo "PKap: install unzip" >&2
    exit 1
  fi

  GRADLE_DIR=$(find "$CACHE/unpack" -mindepth 1 -maxdepth 1 -type d | head -n 1)
  [ -n "$GRADLE_DIR" ] || { echo "PKap: Gradle archive invalid" >&2; exit 1; }
  rm -rf "$DIST"
  mv "$GRADLE_DIR" "$DIST"
fi

exec "$DIST/bin/gradle" "$@"
