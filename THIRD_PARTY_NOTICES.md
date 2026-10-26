# THIRD_PARTY_NOTICES
Record every third-party code **actually reused** (copied or adapted). Libraries pulled in by Gradle/pip are covered by their own metadata, but list any with unusual terms.

| Component | Source / commit | Licence | Where used | Notice text preserved? |
|---|---|---|---|---|
| _(none yet)_ | | | | |

Rules: Apache-2.0 and MIT code only. **Never** add code from AGPL/GPL projects (Mobilerun Portal, OpenAutoJs). See `docs/REFERENCES.md` §0.

## Shizuku API (dev.rikka.shizuku:api 13.1.5)

Used by the shell tier's Shizuku transport (P9-04, ADR-064) — process execution with shell-level privileges through a user-started Shizuku server. Isolated to `app/shell/ShizukuProcessExec.kt`.

```
Apache License 2.0 — Copyright 2018 Rikka
https://github.com/RikkaApps/Shizuku-API — licensed under the Apache License, Version 2.0
```

## ML Kit Text Recognition (com.google.mlkit:text-recognition 16.0.1)

Used by the `vision` capability's OCR engine (P10-05, ADR-072) — on-device Latin text recognition, bundled into the APK (no Play Services dependency). Isolated to `capabilities/vision/src/main/kotlin/app/autohost/capabilities/vision/OcrEngine.kt`.

```
Apache License 2.0 — Copyright Google LLC
https://developers.google.com/ml-kit — bundled artifacts on Google Maven under Apache-2.0
```

## ZXing Core (com.google.zxing:core 3.5.3)

Used by the `vision` capability's QR decoder (P10-05, ADR-072). Isolated to `capabilities/vision/src/main/kotlin/app/autohost/capabilities/vision/QrDecoder.kt`.

```
Apache License 2.0 — Copyright 2010 ZXing authors
https://github.com/zxing/zxing — licensed under the Apache License, Version 2.0
```

## quickjs-kt (io.github.dokar3:quickjs-kt-android 1.0.3)

Used by the JavaScript runtime as the QuickJS engine binding (P11-02, ADR-076). Isolated to `app/src/main/kotlin/app/autohost/runtimejs/QuickJsEngineFactory.kt` — the only file importing `com.dokar.quickjs.*`.

```
Apache License 2.0 — Copyright Dokar (dokar3) and quickjs-kt contributors
https://github.com/dokar3/quickjs-kt — licensed under the Apache License, Version 2.0
Bundles QuickJS (MIT) — Copyright 2017-2021 Charles Brunet / Bellard (https://bellard.org/quickjs/)
```
