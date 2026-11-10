# S04 — One honest task list, Back button, Setup scroll, StrictMode. Branch `work/s04-shell`.
Without this the phone session would test a list that hides the tasks you create (REF 15, 18, 19). Follow `docs/UI_SPEC.md` S02/S03; user text in `strings.xml`.

1. **`TaskCatalog`** (new, ~100 lines, pure + tested): `Flow<List<TaskEntry>>` merging installed tasks (`graph.data.tasks.observeActive()`) and dev-folder tasks
   (`graph.devTasks.tasks`), de-duplicated by task id (dev folder wins). `TaskEntry` = id, name, description, source, runtime, last run, needs-setup flag.
   Unit test with fakes: both sources appear once; a task in both appears once; a new task appears without refresh.
2. Use it **everywhere** that reads `graph.devTasks`: `AppNavHost.kt:156,162` (Library), `SurfacesScreen.kt:50` (tiles, widget, shortcuts; empty texts at 126, 292), the Studio lists
   (`grep -rn devTasks app/src/main`). Delete the dead `LibraryScreen(graph, onOpenTask, onImport)` in `TaskScreens.kt:49` (or make it the single implementation — one only).
3. Library card = name, one-line description, status chip (Ready / Needs setup / Running), last result, **▶ Run**; tap → detail. No ids, no runtime line. Empty state: *"Nothing here yet. Add your first automation."*
   (no adb sentence). Header buttons: search, **+**, ⋮ (Refresh). `Record`, `Studio`, `Ask` move behind Labs (Settings → Labs switch, default off).
4. **Back stack** (`MainActivity.kt:21,24` keep `route`/`runIdArg` as plain fields today and the activity has no `configChanges`): replace with a stack of `(route, arg)` saved with
   `rememberSaveable` (custom `Saver`), `BackHandler(enabled = stack.size > 1) { pop }` at the top of `AppNavHost`; `onNavigate` pushes; tabs replace the root; `openOn("surfaces")` pushes.
   Keep the `Routes` names (tiles/widgets/deep links use them). Test: push three routes, pop them in order; rotation keeps the stack.
5. **Setup screen** (`SetupScreen.kt:37,42`): `verticalScroll(rememberScrollState())` on the column; the inner `Row(Modifier.fillMaxSize())` → `fillMaxWidth()`; move "ALWAYS-ON RECIPE" (line 98) into a collapsed card.
6. **StrictMode (debug builds only)** in `AutoHostApp.onCreate`: `ThreadPolicy…detectAll().penaltyLog()` and `VmPolicy…detectLeakedClosableObjects().penaltyLog()`, routed into the in-app crash log. Never `penaltyDeath`.
7. Robolectric test (from S03): insert a task with `installVersion(...)`, open Library, assert it is listed.
**Gate:** CI green; the new tests exist and pass. STATUS updated.
