# S06 — Stable signing so updates install in place. Branch `work/s06-signing`.
**Problem:** CI uploads a *debug* APK (`app/build.gradle.kts` reads the four `AUTOHOST_*` variables only for release) and `versionCode = 1`. A fresh GitHub machine makes a new debug key each run **[from memory]**,
so each CI APK would have to be uninstalled first — deleting all tasks and data. `docs/RELEASE.md` also says never publish debug-signed builds.
## Owner (once, ~10 min, on your Windows PC)
1. `keytool -genkeypair -v -keystore autohost-alpha.jks -alias autohost -keyalg RSA -keysize 4096 -validity 10000` (asks for two passwords).
2. **Back up the `.jks` and both passwords offline.** Lose them → nobody can update without uninstalling.
3. PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("autohost-alpha.jks")) | Set-Clipboard`
4. GitHub → repo → Settings → Secrets and variables → Actions → add `AUTOHOST_KEYSTORE_B64` (paste), `AUTOHOST_KEYSTORE_PASSWORD`, `AUTOHOST_KEY_ALIAS` (= `autohost`), `AUTOHOST_KEY_PASSWORD`. Then tell the agent "secrets added".
5. Uninstall any older AutoHost from the phone **once** (it carries the old key).
## Agent (after "secrets added")
- In `android-debug-apk.yml`, before the build (skip when the secret is empty so forks still build):
  ```yaml
  - name: Prepare signing key
    env: { B64: "${{ secrets.AUTOHOST_KEYSTORE_B64 }}" }
    run: |
      [ -z "$B64" ] && exit 0
      echo "$B64" | base64 -d > "$RUNNER_TEMP/autohost.jks"
      echo "AUTOHOST_KEYSTORE=$RUNNER_TEMP/autohost.jks" >> "$GITHUB_ENV"
      echo "AUTOHOST_KEYSTORE_PASSWORD=${{ secrets.AUTOHOST_KEYSTORE_PASSWORD }}" >> "$GITHUB_ENV"
      echo "AUTOHOST_KEY_ALIAS=${{ secrets.AUTOHOST_KEY_ALIAS }}" >> "$GITHUB_ENV"
      echo "AUTOHOST_KEY_PASSWORD=${{ secrets.AUTOHOST_KEY_PASSWORD }}" >> "$GITHUB_ENV"
  ```
- `app/build.gradle.kts`: in `buildTypes.debug { … }` add `signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let { signingConfig = it }` (alpha = debug build, no R8, stable key);
  `versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()`, `versionName = "0.1.0-alpha." + that number`. Update `docs/RELEASE.md`: "alpha = debug build signed with the alpha key; the R8 release build is a later, phone-tested step". Never print or commit secrets.
**Gate:** CI produces a signed APK artifact. Owner installs two consecutive APKs over each other and reports whether the tasks survived.
