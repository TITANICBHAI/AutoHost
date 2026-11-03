# Release Signing

ClosePaw release builds use the `release` signing config in
`app/build.gradle.kts`. The signing material is supplied only through
environment variables so keystores and passwords never live in the repo.

## Security Boundary

- NEVER commit a keystore, `.jks`, `.p12`, password file, or signing export.
- NEVER share keystore passwords in chat, tickets, logs, screenshots, or PRs.
- NEVER let agents generate, store, copy, or recover the release keystore.
- The human app owner must run `keytool` and must control every backup.
- Keystore loss can mean you cannot update the app on Play Store EVER. Treat the
  keystore and passwords as production credentials.

## Expected Environment

`app/build.gradle.kts` reads these names:

- `KEYSTORE_PATH`: path to the upload keystore file, stored outside the repo.
- `KEYSTORE_PASSWORD`: password for the keystore.
- `KEY_ALIAS`: alias for the upload key. Use `closepaw`.
- `KEY_PASSWORD`: password for the key entry.

Gradle defaults `KEY_ALIAS` to `closepaw` and `KEY_PASSWORD` to
`KEYSTORE_PASSWORD` when env vars are missing so IDE sync and debug builds stay
usable. The release scripts intentionally require all four variables to be set
and non-empty.

## 1. Choose Storage

Use a private location outside the checkout, for example:

```bash
mkdir -p "$HOME/secrets/closepaw"
chmod 700 "$HOME/secrets/closepaw"
```

Store the keystore and passwords in 1Password or an equivalent secrets manager.
If your release process supports a hardware token or HSM-backed flow, prefer
that. Keep at least two encrypted backups in separate places, and record the
keystore SHA-256 in your release notes so restores can be verified.

## 2. Set Signing Env Vars

Set these in a trusted shell. Avoid typing passwords directly into shell history.

```bash
export KEYSTORE_PATH="$HOME/secrets/closepaw/closepaw-upload.p12"
export KEY_ALIAS="closepaw"

read -rsp "KEYSTORE_PASSWORD / KEY_PASSWORD: " RELEASE_KEY_PASSWORD
export KEYSTORE_PASSWORD="$RELEASE_KEY_PASSWORD"
export KEY_PASSWORD="$RELEASE_KEY_PASSWORD"
unset RELEASE_KEY_PASSWORD
echo
```

Use a unique high-entropy password. For the setup helper's PKCS12 recipe, set
`KEY_PASSWORD` to the same value as `KEYSTORE_PASSWORD`. Both variables must
still be explicitly supplied for release builds.

## 3. Generate The Upload Keystore

Run the setup helper. It validates the env vars, refuses to continue if
`KEYSTORE_PATH` already exists, explains the flags, and prints the exact
`keytool` command for you to copy and run.

```bash
scripts/sign-release-setup.sh
```

The printed command uses `-validity 10000`, which is more than 25 years. Google
Play requires signing certificates to remain valid until at least 22 Oct 2033;
being more conservative avoids a future release blocker.

After you run `keytool`, lock down the file:

```bash
chmod 600 "$KEYSTORE_PATH"
```

## 4. Build The Play Upload AAB

Keep the same env vars in your shell and run:

```bash
scripts/release-build.sh
```

The script runs:

```bash
./gradlew clean bundleRelease
```

It then prints the generated `.aab` path and SHA-256 digest for the release
record. Upload the `.aab` to Play Console.

## 5. Play App Signing

Prefer Play App Signing for production. In the modern flow, Google manages the
app signing key and you upload releases signed with your upload key. This lowers
the blast radius of an upload-key problem and is the expected Play Store path.

Even with Play App Signing, protect the upload keystore as critical production
infrastructure. Losing it blocks releases until recovery is complete; without
Play App Signing, losing the key that signs updates can permanently strand the
app package.
