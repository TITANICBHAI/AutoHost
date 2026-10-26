#!/usr/bin/env bash

autohost_prepare_android_build_env() {
  local java_version sdk_root local_sdk

  if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java is required. Install JDK 17 in the Replit environment." >&2
    return 1
  fi
  if ! command -v gradle >/dev/null 2>&1; then
    echo "ERROR: Gradle is required. Install the Gradle Replit module." >&2
    return 1
  fi

  java_version="$(java -XshowSettings:properties -version 2>&1 \
    | sed -nE 's/^[[:space:]]*java.specification.version = ([0-9]+).*$/\1/p' \
    | head -n 1)"
  if [[ "$java_version" != "17" ]]; then
    echo "ERROR: AutoHost requires JDK 17; found ${java_version:-unknown}." >&2
    return 1
  fi

  sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$sdk_root" && -f "$ROOT_DIR/local.properties" ]]; then
    local_sdk="$(sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
      "$ROOT_DIR/local.properties" | tail -n 1)"
    sdk_root="${local_sdk//\\ / }"
  fi
  if [[ -z "$sdk_root" ]]; then
    echo "ERROR: Android SDK not found. Set ANDROID_SDK_ROOT (or ANDROID_HOME) to an installed SDK." >&2
    echo "Install and accept the Android SDK licenses before running the Gradle workflows." >&2
    return 1
  fi

  sdk_root="$(cd "$sdk_root" 2>/dev/null && pwd -P)" || {
    echo "ERROR: Android SDK directory does not exist: $sdk_root" >&2
    return 1
  }
  if [[ ! -f "$sdk_root/platforms/android-36/android.jar" ]]; then
    echo "ERROR: Android API 36 is required at $sdk_root/platforms/android-36." >&2
    return 1
  fi

  export ANDROID_SDK_ROOT="$sdk_root"
  export ANDROID_HOME="$sdk_root"
  echo "Using JDK 17 and Android SDK at $ANDROID_SDK_ROOT"
  java -version
  gradle --version | sed -n '1,8p'
}
