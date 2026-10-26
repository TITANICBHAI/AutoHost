# RELEASE — signing, registration and release hygiene (P11-09, Gate 11-e)

The release route is **sideload + limited distribution** (plan §21-10): the package is
registered under a limited-distribution developer account, the release key lives OUTSIDE the
repo, and every release build keeps the risky surfaces off by default. This file is the exact
checklist; nothing here requires improvisation.

## 1. Release signing (keystore outside the repo)

The `release` signing config reads environment variables — nothing secret is ever committed
(AGENTS.md §4):

```bash
export AUTOHOST_KEYSTORE=/secure/path/autohost-release.jks
export AUTOHOST_KEYSTORE_PASSWORD='…'
export AUTOHOST_KEY_ALIAS=autohost
export AUTOHOST_KEY_PASSWORD='…'

gradle :app:assembleCoreRelease            # signed automatically when all four vars are set
gradle :app:assembleFullRelease
```

Without the variables the build still produces an **unsigned** artifact (CI stays green); the
owner signs it on their machine:

```bash
apksigner sign --ks "$AUTOHOST_KEYSTORE" --out app-core-release-signed.apk \
  app/build/outputs/apk/core/release/app-core-release-unsigned.apk
apksigner verify --print-certs app-core-release-signed.apk
```

Key hygiene: one 2048-bit+ RSA key, generated with `keytool -genkeypair -keyalg RSA -validity 10000`;
back it up OFFLINE (the registered package is bound to this key — losing it loses updates).

## 2. Verified-developer registration (Play Protect honesty)

1. Register the `app.autohost` package under a limited-distribution developer account.
2. Expect the first sideload to warn ("unknown developer") — the README and the Setup screen
   say so honestly; never ask the user to disable Play Protect.
3. Record the registration status in `docs/DEVICES.md` (a `VERIFIED_ON_HARDWARE` row once a
   real phone installs without an unresolved warning).
4. If Google's developer-verification flow requires an app-signing attestation, attach the
   `apksigner verify --print-certs` output — do not publish debug-signed builds, ever.

## 3. Release build hardening checklist (X-05)

| Surface | Release state | Enforced by |
|---|---|---|
| Dev API (HTTP) | OFF until the user enables it; token required | `DevApiPolicy.enabled = false` (P8), Settings toggle |
| Dev API LAN binding | 127.0.0.1 unless the `dev_api_remote` switch is on | `DevApiPolicy.allowRemote` + restart rebind |
| Reverse bridge | Not started unless the user enters a bridge URL | `ReverseLinkController` (P11-07) |
| Webhooks | localhost, token-mandatory | `WebhookServer` (ADR-061) |
| Insecure-feature switches | all OFF (deny wins) | `InsecureFeatures` + tests |
| Import guard (raw bridge) | ON | `ImportPipeline(rawBridgeGuard = true)` |
| Cleartext traffic | no `usesCleartextTraffic`; `net.http` allows https (+ http by explicit task scope policy) | `NetPolicy` + manifest |
| Backup | secrets never exported (names only) | `BackupCodec` refusal rules (ADR-082) |
| Log/evidence retention | 30/14 days, redacted | retention sweeper + `Redactor` |

## 4. Licence hygiene (Gate 11-e, CI-enforced)

- `tools/licence_check.py` fails the build when a catalog dependency lacks a
  `THIRD_PARTY_NOTICES.md` row or a recorded allowlist entry.
- Clean-room rule unchanged (AGENTS.md §3.9): AGPL/GPL reference projects are read for ideas
  only; Apache/MIT adaptations gain a notices row.
- The upgrade path for a full per-artifact report is `app.cash.licensee` (reserved in the
  allowlist) — adopt it when the dependency count makes the row table unwieldy.

## 5. Upgrade/migration gates before tagging a release

1. `gradle :core:engine:test` — `UpgradeMigrationTest` (manifest chain, HOST_TOO_OLD, SemVer).
2. `gradle :core:data:test` — Room `SchemaMigrationTest` (androidTest; runs at the device).
3. Install the PREVIOUS release over the new one on the phone and open Setup & Health —
   the migration must be invisible except through `docs/PROGRESS.md` evidence.
4. Tag format: `v<semver>`; the backup document's `schema` bump and the plugin-API bump are
   ADR-recorded (ADR-079 band, ADR-082 doc v1) and never ride along silently.

## 6. The one-command release order

```bash
python3 tools/progress.py --check
python3 tools/licence_check.py
cd sdk-python && python3 -m unittest discover -s tests -t tests -p "test_*.py"; cd ..
python3 -m unittest discover -s tests -p "test_*.py"
gradle test
gradle :app:assembleCoreRelease :app:assembleFullRelease
# then §1 signing, §2 registration, and the Gates that still need the phone (docs/GATES.md)
```
