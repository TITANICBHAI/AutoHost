---
name: Android target-device compatibility
description: User-provided AutoHost target phones and the selected Android ABI/Python compatibility.
---

The user's target devices include a Realme C3 (RMX2027), whose supplied properties report Android 11/API 30, `arm64-v8a`, and 32-bit compatibility ABIs, and a Samsung SM-M315F running Android 12/API 31 with `arm64-v8a`. The user chose to keep Python 3.13 and exclude 32-bit ARM from the app build. Android 10/API 29 also meets the app's declared minimum API when running on an arm64-capable device.

**Why:** These are user-provided target devices and an explicit compatibility choice that are not discoverable from the source tree.

**How to apply:** Keep an `arm64-v8a` build for these phones; do not promise an `armeabi-v7a` build with Python 3.13.
