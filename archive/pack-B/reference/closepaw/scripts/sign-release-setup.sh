#!/usr/bin/env bash
set -euo pipefail

fail() {
  printf 'sign-release-setup: %s\n' "$*" >&2
  exit 1
}

require_env() {
  local name="$1"
  if [[ -z "${!name-}" ]]; then
    fail "$name is required and must not be empty. Export it before running this script."
  fi
}

shell_quote() {
  printf '%q' "$1"
}

required_env=(
  KEYSTORE_PATH
  KEYSTORE_PASSWORD
  KEY_ALIAS
  KEY_PASSWORD
)

for name in "${required_env[@]}"; do
  require_env "$name"
done

if [[ -e "$KEYSTORE_PATH" ]]; then
  fail "refusing to proceed because KEYSTORE_PATH already exists: $KEYSTORE_PATH"
fi

if [[ "$KEY_PASSWORD" != "$KEYSTORE_PASSWORD" ]]; then
  fail "this PKCS12 keytool recipe requires KEY_PASSWORD to match KEYSTORE_PASSWORD; export both explicitly to the same value"
fi

keystore_dir="$(dirname -- "$KEYSTORE_PATH")"
if [[ ! -d "$keystore_dir" ]]; then
  fail "parent directory does not exist: $keystore_dir"
fi

if ! command -v keytool >/dev/null 2>&1; then
  fail "keytool was not found on PATH. Install or select a JDK, then retry."
fi

quoted_keystore_path="$(shell_quote "$KEYSTORE_PATH")"
quoted_key_alias="$(shell_quote "$KEY_ALIAS")"

cat <<EOF
Release upload keystore setup
=============================

All required signing environment variables are present:
  KEYSTORE_PATH=$KEYSTORE_PATH
  KEYSTORE_PASSWORD=(set, not printed)
  KEY_ALIAS=$KEY_ALIAS
  KEY_PASSWORD=(set, not printed)

No keystore was created. Agents and scripts must not generate or store release
keystores for you. Copy and run this command yourself in a trusted shell:

keytool -genkey -v \\
  -storetype PKCS12 \\
  -keystore $quoted_keystore_path \\
  -alias $quoted_key_alias \\
  -keyalg RSA \\
  -keysize 4096 \\
  -validity 10000 \\
  -storepass "\$KEYSTORE_PASSWORD" \\
  -keypass "\$KEY_PASSWORD"

Flag guide:
  -genkey          Creates a new key pair and certificate entry.
  -v               Prints verbose keytool output so you can verify what happened.
  -storetype       Uses PKCS12, the modern portable Java keystore format.
  -keystore        Writes the keystore to KEYSTORE_PATH.
  -alias           Names the upload key entry inside the keystore.
  -keyalg/-keysize Creates a 4096-bit RSA upload key.
  -validity        Keeps the certificate valid for 10000 days, over 25 years.
  -storepass       Reads the keystore password from your shell environment.
  -keypass         Reads the key password from your shell environment.

After keytool finishes, protect the keystore and verify it exists:
  chmod 600 $quoted_keystore_path
  ls -l $quoted_keystore_path

EOF
