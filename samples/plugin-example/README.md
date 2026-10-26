# Example capability plugin (P11-02, ADR-079)

A standalone APK that contributes one capability — `plugin.example.clock` (`now`, `tick`) — to
AutoHost without any host code change. It is the Gate 11-d vehicle: two tasks declare
`plugin.example.clock` in `needs.capabilities`, and upgrading or reinstalling the plugin changes
nothing in either task.

## Layout

```
samples/plugin-example/
├─ settings.gradle.kts      # standalone build; maps :plugin-sdk onto the host repo's module
├─ gradle/libs.versions.toml
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml           # exported service + the discovery intent action
      └─ kotlin/.../ExamplePluginService.kt
```

## Build and install

```bash
cd samples/plugin-example
gradle :app:assembleDebug          # produces app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

AutoHost discovers the service through the `app.autohost.plugin.CAPABILITY_PROVIDER` intent
action (declared in the host's `<queries>`), binds it, validates `describe()` fail-closed, and
registers `plugin.example.clock` with the broker. Restart AutoHost (or toggle the plugin APK's
enabled state and refresh from Settings → Plugins) to re-run discovery.

## Use it from a task

```python
import autohost
result = autohost.call("plugin.example.clock", "now", {})
```

The task manifest must declare the capability:

```json
"needs": {"capabilities": [{"id": "plugin.example.clock"}]}
```

The run is granted `plugin.example.clock.use` (the plugin's declared permission, mapped through
the host's permission index — ADR-079). If the plugin is missing, the call fails
`CAPABILITY_UNAVAILABLE` with the honest availability reason — it never silently no-ops.

## Contract rules the host enforces (fail closed)

- `apiLevel()` must be within the host's supported band (currently 1..1).
- Capability ids MUST be namespaced `plugin.<vendor>.<name>` and must not collide with a host
  capability — collisions reject the WHOLE plugin, not just the entry.
- `describe()` is fully validated; one malformed entry rejects the whole plugin.
- Availability and invoke are JSON passthroughs; malformed envelopes become typed host errors
  (`PLUGIN_ENVELOPE_MALFORMED`), never host crashes.
