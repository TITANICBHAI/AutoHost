#!/usr/bin/env bash

ANDROID_BUILD_ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
source "$ANDROID_BUILD_ENV_DIR/tool-cache.sh"

declare -a AUTOHOST_ANDROID_TEMP_DIRS=()

autohost_cleanup_android_build_env() {
  local temporary_dir
  for temporary_dir in "${AUTOHOST_ANDROID_TEMP_DIRS[@]:-}"; do
    [[ -n "$temporary_dir" ]] && rm -rf -- "$temporary_dir"
  done
}

autohost_android_temp_dir() {
  local cache_root="$1"
  local temporary_dir
  mkdir -p -- "$cache_root/tmp"
  temporary_dir="$(mktemp -d "$cache_root/tmp/autohost-android.XXXXXX")"
  AUTOHOST_ANDROID_TEMP_DIRS+=("$temporary_dir")
  printf '%s\n' "$temporary_dir"
}

autohost_canonical_path() {
  local candidate="$1"
  if [[ "$candidate" != /* ]]; then
    candidate="${ROOT_DIR:-$PWD}/$candidate"
  fi
  readlink -m -- "$candidate"
}

autohost_assert_outside_repo() {
  local candidate
  local label="$2"
  candidate="$(autohost_canonical_path "$1")"
  case "$candidate" in
    "${ROOT_DIR:-$PWD}"|"${ROOT_DIR:-$PWD}"/*)
      echo "ERROR: $label must be outside the Git checkout: $candidate" >&2
      return 1
      ;;
  esac
}

autohost_java_major_version() {
  local java_bin="$1"
  "$java_bin" -XshowSettings:properties -version 2>&1 \
    | sed -nE 's/^[[:space:]]*java.specification.version = ([0-9]+).*$/\1/p' \
    | head -n 1
}

autohost_java_home_for() {
  local resolved_java
  resolved_java="$(readlink -f "$1")"
  cd -P "$(dirname "$resolved_java")/.." && pwd -P
}

autohost_download_jdk17() {
  local cache_root="$1"
  local adoptium_arch="$2"
  local java_cache="$cache_root/jdk-17"
  local temporary_dir extracted_jdk staging_jdk

  if [[ -x "$java_cache/bin/java" ]] \
    && [[ "$(autohost_java_major_version "$java_cache/bin/java")" == "17" ]]; then
    JAVA_HOME="$java_cache"
    echo "Using cached JDK 17 at $JAVA_HOME"
    return 0
  fi

  temporary_dir="$(autohost_android_temp_dir "$cache_root")"
  mkdir -p "$temporary_dir/extracted"
  echo "JDK 17 not found; downloading a Linux JDK from Adoptium..."
  curl --fail --location --retry 3 --connect-timeout 20 \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/${adoptium_arch}/jdk/hotspot/normal/eclipse" \
    --output "$temporary_dir/jdk-17.tar.gz"
  tar -xzf "$temporary_dir/jdk-17.tar.gz" -C "$temporary_dir/extracted"

  extracted_jdk="$(find "$temporary_dir/extracted" -mindepth 1 -maxdepth 1 -type d -print -quit)"
  if [[ -z "$extracted_jdk" || ! -x "$extracted_jdk/bin/java" ]] \
    || [[ "$(autohost_java_major_version "$extracted_jdk/bin/java")" != "17" ]]; then
    echo "ERROR: Adoptium's downloaded archive did not contain a usable JDK 17." >&2
    return 1
  fi

  staging_jdk="$cache_root/.jdk-17-install-$$"
  rm -rf -- "$staging_jdk"
  mv -- "$extracted_jdk" "$staging_jdk"
  rm -rf -- "$java_cache"
  mv -- "$staging_jdk" "$java_cache"
  JAVA_HOME="$java_cache"
  echo "Installed JDK 17 at $JAVA_HOME"
}

autohost_find_java17() {
  local java_bin=""
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] \
    && [[ "$(autohost_java_major_version "$JAVA_HOME/bin/java")" == "17" ]]; then
    java_bin="$JAVA_HOME/bin/java"
  elif command -v java >/dev/null 2>&1 \
    && [[ "$(autohost_java_major_version "$(command -v java)")" == "17" ]]; then
    java_bin="$(command -v java)"
  fi
  printf '%s\n' "$java_bin"
}

autohost_ensure_gradle() {
  local cache_root="$1"
  local installed_gradle installed_version gradle_cache temporary_dir expected_sha
  local gradle_version="8.13"
  local gradle_sha256="20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"

  if command -v gradle >/dev/null 2>&1; then
    installed_gradle="$(command -v gradle)"
    installed_version="$("$installed_gradle" --version --console=plain \
      | sed -nE 's/^Gradle ([0-9.]+)$/\1/p' | head -n 1)"
    if [[ "$installed_version" == "$gradle_version" ]]; then
      AUTOHOST_GRADLE_BIN="$installed_gradle"
      return 0
    fi
  fi

  gradle_cache="$cache_root/gradle-$gradle_version"
  if [[ -x "$gradle_cache/bin/gradle" ]]; then
    AUTOHOST_GRADLE_BIN="$gradle_cache/bin/gradle"
    echo "Using cached Gradle $gradle_version at $gradle_cache"
    return 0
  fi

  temporary_dir="$(autohost_android_temp_dir "$cache_root")"
  echo "Gradle $gradle_version not found; downloading it from Gradle's official distribution service..."
  curl --fail --location --retry 3 --connect-timeout 20 \
    "https://services.gradle.org/distributions/gradle-${gradle_version}-bin.zip" \
    --output "$temporary_dir/gradle.zip"
  curl --fail --location --retry 3 --connect-timeout 20 \
    "https://services.gradle.org/distributions/gradle-${gradle_version}-bin.zip.sha256" \
    --output "$temporary_dir/gradle.zip.sha256"
  expected_sha="$(tr -d '[:space:]' < "$temporary_dir/gradle.zip.sha256")"
  if [[ "$expected_sha" != "$gradle_sha256" ]]; then
    echo "ERROR: Gradle's published checksum did not match the pinned Gradle $gradle_version checksum." >&2
    return 1
  fi
  printf '%s  %s\n' "$gradle_sha256" "$temporary_dir/gradle.zip" | sha256sum --check
  unzip -tq "$temporary_dir/gradle.zip"
  unzip -q "$temporary_dir/gradle.zip" -d "$temporary_dir"
  if [[ ! -x "$temporary_dir/gradle-${gradle_version}/bin/gradle" ]]; then
    echo "ERROR: Gradle archive has an unexpected layout." >&2
    return 1
  fi

  rm -rf -- "$gradle_cache"
  mv -- "$temporary_dir/gradle-${gradle_version}" "$gradle_cache"
  AUTOHOST_GRADLE_BIN="$gradle_cache/bin/gradle"
  echo "Installed Gradle $gradle_version at $gradle_cache"
}

autohost_sdk_root_from_project() {
  local sdk_root="$1"
  local local_sdk=""

  if [[ -f "$ROOT_DIR/local.properties" ]]; then
    local_sdk="$(sed -nE 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' \
      "$ROOT_DIR/local.properties" | tail -n 1)"
    local_sdk="$(printf '%s\n' "$local_sdk" | sed -e 's/\\ / /g' -e 's/\\:/:/g' -e 's/\\\\/\\/g')"
  fi

  if [[ -n "$local_sdk" ]]; then
    if [[ -n "$sdk_root" ]] \
      && [[ "$(autohost_canonical_path "$sdk_root")" != "$(autohost_canonical_path "$local_sdk")" ]]; then
      echo "ERROR: local.properties sdk.dir conflicts with ANDROID_SDK_ROOT/ANDROID_HOME." >&2
      echo "Set both to the same SDK directory or remove the local sdk.dir entry." >&2
      return 1
    fi
    sdk_root="$local_sdk"
  fi

  printf '%s\n' "$sdk_root"
}

autohost_install_android_command_line_tools() {
  local sdk_root="$1"
  local cache_root="$2"
  local sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
  local temporary_dir tools_root staging_tools backup_tools
  local revision="16111833"
  local archive_sha1="e025545c62a8e64c7559119566a569fb1dec5f60"

  if [[ -x "$sdkmanager" ]]; then
    printf '%s\n' "$sdkmanager"
    return 0
  fi

  temporary_dir="$(autohost_android_temp_dir "$cache_root")"
  echo "Android SDK command-line tools not found; downloading Google's Linux tools..." >&2
  curl --fail --location --retry 3 --connect-timeout 20 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${revision}_latest.zip" \
    --output "$temporary_dir/commandline-tools.zip"
  printf '%s  %s\n' "$archive_sha1" "$temporary_dir/commandline-tools.zip" | sha1sum --check >&2
  unzip -tq "$temporary_dir/commandline-tools.zip" >&2
  mkdir -p "$temporary_dir/extracted" "$sdk_root/cmdline-tools"
  unzip -q "$temporary_dir/commandline-tools.zip" -d "$temporary_dir/extracted"
  if [[ ! -x "$temporary_dir/extracted/cmdline-tools/bin/sdkmanager" ]]; then
    echo "ERROR: Google's command-line tools archive has an unexpected layout." >&2
    return 1
  fi

  tools_root="$sdk_root/cmdline-tools"
  staging_tools="$tools_root/.latest-install-$$"
  backup_tools="$tools_root/.latest-backup-$$"
  rm -rf -- "$staging_tools" "$backup_tools"
  mv -- "$temporary_dir/extracted/cmdline-tools" "$staging_tools"
  if [[ -e "$tools_root/latest" ]]; then
    mv -- "$tools_root/latest" "$backup_tools"
  fi
  if ! mv -- "$staging_tools" "$tools_root/latest"; then
    if [[ -e "$backup_tools" && ! -e "$tools_root/latest" ]]; then
      mv -- "$backup_tools" "$tools_root/latest"
    fi
    echo "ERROR: Could not install Android SDK command-line tools." >&2
    return 1
  fi
  rm -rf -- "$backup_tools"
  printf '%s\n' "$sdkmanager"
}

autohost_accept_and_install_android_sdk() {
  local sdk_root="$1"
  local cache_root="$2"
  local sdkmanager licenses_status

  sdkmanager="$(autohost_install_android_command_line_tools "$sdk_root" "$cache_root")"
  if [[ ! -x "$sdkmanager" ]]; then
    echo "ERROR: sdkmanager was not installed at $sdkmanager." >&2
    return 1
  fi

  echo "Accepting Android SDK licenses..."
  set +o pipefail
  if yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses; then
    licenses_status=0
  else
    licenses_status=$?
  fi
  set -o pipefail
  if (( licenses_status != 0 )); then
    echo "ERROR: Android SDK license acceptance failed (sdkmanager exit $licenses_status)." >&2
    return "$licenses_status"
  fi

  echo "Installing Android API 36, Build Tools 36.0.0, and Platform Tools..."
  "$sdkmanager" --sdk_root="$sdk_root" \
    "platforms;android-36" \
    "build-tools;36.0.0" \
    "platform-tools"

  if [[ ! -f "$sdk_root/platforms/android-36/android.jar" ]]; then
    echo "ERROR: Android API 36 was not installed at $sdk_root/platforms/android-36." >&2
    return 1
  fi
  if [[ ! -x "$sdk_root/build-tools/36.0.0/aapt" ]]; then
    echo "ERROR: Android Build Tools 36.0.0 were not installed correctly." >&2
    return 1
  fi
}

autohost_prepare_android_build_env() {
  local cache_root java_bin adoptium_arch sdk_root_candidate sdk_root
  local tool

  if [[ "$(uname -s)" != "Linux" ]]; then
    echo "ERROR: Android toolchain bootstrap currently supports Linux only." >&2
    return 1
  fi
  case "$(uname -m)" in
    x86_64|amd64) adoptium_arch="x64" ;;
    aarch64|arm64) adoptium_arch="aarch64" ;;
    *)
      echo "ERROR: Unsupported CPU architecture: $(uname -m)" >&2
      return 1
      ;;
  esac
  for tool in curl tar unzip sha1sum sha256sum find readlink; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      echo "ERROR: Required bootstrap tool '$tool' is missing from PATH." >&2
      return 1
    fi
  done

  cache_root="$(autohost_tool_cache_dir "$ROOT_DIR")"
  mkdir -p "$cache_root/android-user"
  export ANDROID_USER_HOME="$cache_root/android-user"
  export GRADLE_USER_HOME="$cache_root/gradle-user-home"
  mkdir -p "$GRADLE_USER_HOME"

  java_bin="$(autohost_find_java17)"
  if [[ -n "$java_bin" ]]; then
    JAVA_HOME="$(autohost_java_home_for "$java_bin")"
    echo "Using existing JDK 17 at $JAVA_HOME"
  else
    autohost_download_jdk17 "$cache_root" "$adoptium_arch"
  fi
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"

  autohost_ensure_gradle "$cache_root"
  export AUTOHOST_GRADLE_BIN

  sdk_root_candidate="${AUTOHOST_ANDROID_SDK_ROOT:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}"
  sdk_root_candidate="$(autohost_sdk_root_from_project "$sdk_root_candidate")"
  if [[ -z "$sdk_root_candidate" ]]; then
    sdk_root_candidate="$cache_root/android-sdk"
  fi
  autohost_assert_outside_repo "$sdk_root_candidate" "Android SDK paths" || return 1
  sdk_root="$(autohost_canonical_path "$sdk_root_candidate")"
  mkdir -p -- "$sdk_root"
  sdk_root="$(cd -P "$sdk_root" && pwd -P)"
  autohost_assert_outside_repo "$sdk_root" "Android SDK paths" || return 1

  export ANDROID_SDK_ROOT="$sdk_root"
  export ANDROID_HOME="$sdk_root"
  export PATH="$sdk_root/platform-tools:$sdk_root/cmdline-tools/latest/bin:$PATH"

  echo "JAVA_HOME=$JAVA_HOME"
  "$JAVA_HOME/bin/java" -version
  echo "Gradle: $AUTOHOST_GRADLE_BIN"
  "$AUTOHOST_GRADLE_BIN" --version --console=plain | sed -n '1,6p'
  echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
  autohost_accept_and_install_android_sdk "$ANDROID_SDK_ROOT" "$cache_root"
}
