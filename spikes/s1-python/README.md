# S1a — Chaquopy + Requests in one process

This is throwaway Phase 0 code. It is isolated under `spikes/` and is not a
production Android module. The production app remains Kotlin; this minimal
Java host avoids adding a Kotlin or AndroidX dependency to this smoke harness.

The launcher starts Chaquopy on a background executor, imports `requests`,
and displays the Android and Python process IDs. Matching IDs demonstrate
that the interpreter is running in the host app process. The check does not
make a network request and does not validate worker isolation, AIDL, or ABI
support on the primary phone; those belong to later S1 tasks.

## Pinned versions

- Chaquopy Gradle plugin: `17.1.0`
- Python: `3.13`
- Android Gradle Plugin: `8.13.2`
- `requests`: `2.34.2` (PyPI reports Apache-2.0)
- `compileSdk` / `targetSdk`: `35`; `minSdk`: `29`
- ABIs: `arm64-v8a` and `x86_64`; actual phone ABI support remains unverified

Chaquopy 17.1.0 supports Python 3.13, Android Gradle Plugin 7.3–9.4, and
Android API 24+. Current Chaquopy releases are open-source; its official
licensing page says the restrictions were removed starting with 12.0.1.

Sources:

- [Chaquopy Android setup](https://chaquo.com/chaquopy/doc/current/android.html)
- [Chaquopy version compatibility](https://chaquo.com/chaquopy/doc/current/versions.html)
- [Chaquopy licensing](https://chaquo.com/chaquopy/license/)
- [Requests 2.34.2 on PyPI](https://pypi.org/project/requests/2.34.2/)

`requests` is a direct Apache-2.0 dependency for this spike. Transitive pip
dependency versions have not been resolved or audited because build
verification is deferred. Do not treat this spike's temporary dependency set
as approved for the production app; complete the exact dependency inventory
when production dependencies are selected.

## Verification status

The source is present. A GitHub Actions smoke workflow is configured to build
the APK and launch it on an x86_64 API 35 emulator. The workflow has not yet
run, so this spike is not verified. No APK build is run in Replit; primary-phone
ABI and real-device behavior remain unverified.
