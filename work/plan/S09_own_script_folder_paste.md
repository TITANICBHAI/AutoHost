# S09 — Use my own script / folder / pasted code. Branches `work/s09a-own-script`, `work/s09b-own-folder` (two sessions).
Everything ends in the **existing** pipeline: files into a temp folder → `ImportPipeline.review(Source.DevFolder(dir))` → plain report → install (`source = "local-file" | "local-folder" | "pasted"`). Nothing bypasses review, grants or compat checks.
(The runner already accepts plain scripts and routes `print()` — S01/P06.)
## S09a — `ManifestFactory`, the Add sheet, file + paste
1. **`ManifestFactory`** (pure Kotlin, JVM test): **use `testing/golden/script_manifest_vectors.json`** (20 cases, every manifest already validated against `schemas/task.schema.json`).
   Input = kind (script|flow), display name, entry, permission choices (control_apps_on + control_apps, internet + internet_domains, notifications, own_files), `idSuffix` (= `Ids.ulid(clock.nowMs()).takeLast(4).lowercase()`, passed in so the function is pure).
   Output = `task.json` text or error keys (`name_required, entry_must_be_py, entry_must_be_flow_file, entry_path_invalid, net_http_needs_domain, app_control_needs_app`). Test: parse the produced JSON and compare to the vector's `manifest` as JSON trees; run `ManifestValidator` on every manifest (zero errors).
   Facts the vectors encode: id `local.<slug>-<suffix>` (id pattern `^[a-z][a-z0-9]*(\.[a-z0-9][a-z0-9-]*)+$`; slug = lowercase a-z0-9 and `-`, ≤ 40 chars, fallback `script`); name ≤ 80 chars; picked file/pasted code is stored as `main.py`;
   grants are **permission ids with a scope** (not capability families): control apps → `device.app.launch`, `device.app.query`, `device.ui.observe`, `device.ui.act`, each `scope.packages`; internet → `net.http` with `scope.domains`; `notify.post`, `files.private` need no scope;
   UI scripts get `ui_exclusive` and `needs_unlocked_screen`; scripts: `timeout_minutes` 30, `verification` `"off"`.
2. **`PermissionLabels`**: permission id + scope → one sentence ("See what is on the screen in: Settings, Chrome" / "Contact these websites: example.com" / "Show notifications"). Test: fails if any permission id in `schemas/capability-specs.w1.json` has no label.
3. **"+ Add" sheet** (UI_SPEC S02): *Start from an example · Use my Python script · Use a folder · Paste a script · Open a shared file (.aatask)* (+ *From link* later). Entries not yet built are hidden, not disabled.
4. **Use my Python script:** `ActivityResultContracts.OpenDocument()` with MIME `["text/x-python","text/plain","*/*"]` (`.py` MIME is unreliable; check the name ends in `.py`); read on `Dispatchers.IO`, ≤ 1 MB, UTF-8, no NUL.
   Screen: **Name** · four switches *Control other apps* (→ app picker, ≥ 1 app) · *Use the internet* (→ "which websites?" chips; keep only the domain) · *Show notifications* · *Keep its own files* — all **off** by default · **Check & add**.
   Suggest, don't decide: scan the script text for the SDK modules it uses and pre-switch the matching group as a suggestion (build the module→permission map from the generated stubs, not by hand). First confirm whether `net.http` accepts a wildcard domain; if not, "any website" is not offered.
   Unbundled imports are found by `ImportScanner`; show its suggestion in plain words.
5. **Paste a script:** large monospace box + Name; same steps; size ≤ 200 KB.
6. Plain report (UI_SPEC §7): "Looks fine / Needs setup: Accessibility is off [Turn on] / Can't add: …"; details behind a link. Clean the temp folder on every exit path.
## S09b — folder + replace
7. `OpenDocumentTree()`; copy with `DocumentFile` on `Dispatchers.IO` into `cache/import/<uuid>/`, progress + Cancel. **Caps = `BundleExtractor`'s** (≤ 2,000 files, ≤ 32 MB per file, ≤ 128 MB total); skip names starting with `.` and dirs `__pycache__, .git, node_modules, venv, .venv`; reject names containing `..`/`/` after decoding.
   Has `task.json` at the root → review as is; no `task.json` → "Which file should run?" (preselect `main.py`, else `__main__.py`, else the only `.py`) → `ManifestFactory` with that entry (a sub-folder path is allowed; the folder is the task dir so sibling imports work).
8. Keep the link: `takePersistableUriPermission`, store the URI on the task; **Update from folder** re-copies and installs a new patch version (config/profiles kept). Auto-update before each run: setting, off by default.
9. **Replace script file…** (same id, bumped version; keep the old version for rollback).
**Gates:** CI green with the vector tests; phone: a 3-line `print("hi")` file → Check & add → Run → SUCCEEDED and the LOG shows `hi`; a folder with `main.py` + `helper.py` runs; pasted code runs; each is in the list after leaving and returning.
