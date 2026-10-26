package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.core.designsystem.components.SectionHeader
import kotlinx.coroutines.launch

/**
 * P2-19: Settings basics + the Developer screen (Dev API toggle, token display + copy, port).
 * Dev API ships OFF (docs/DEVAPI.md §1); enabling starts the localhost server under the rotated token.
 */
@Composable
fun SettingsScreen(graph: AppGraph, onOpenHealth: () -> Unit, onOpenSurfaces: () -> Unit = {}) {
    var devApiOn by remember { mutableStateOf(graph.devApiEnabled) }
    var token by remember { mutableStateOf(graph.devApiTokens.currentOrNull() ?: "") }
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var backend by remember { mutableStateOf(graph.schedulerBackendPreference) }
    var switchState by remember { mutableStateOf(graph.insecureSwitches.state) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        SectionHeader("Setup")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Setup & Health", style = MaterialTheme.typography.titleSmall)
                Text("Accessibility, notifications, alarms, battery, Dev API — all checks in one place.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = onOpenHealth, modifier = Modifier.padding(top = 8.dp)) { Text("Open") }
            }
        }
        SectionHeader("Scheduler")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Scheduler backend", style = MaterialTheme.typography.titleSmall)
                Text(
                    "auto prefers exact alarms and falls back to WorkManager; force a backend only if " +
                        "your OEM treats one better. With Work timing may drift by minutes (badge shown).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto", "alarm", "work", "job").forEach { option ->
                        androidx.compose.material3.FilterChip(
                            selected = backend == option,
                            onClick = {
                                backend = option
                                scope.launch { graph.setSchedulerBackendPreference(option) }
                            },
                            label = { Text(option) },
                        )
                    }
                }
                Text(
                    "Screen guard: while enabled, a foreground service keeps the screen available for " +
                        "scheduled tasks (wake + dismiss non-secure locks; secure locks wait for you).",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row {
                    TextButton(onClick = {
                        app.autohost.capabilities.screen.ScreenGuardService.start(graph.appContext)
                    }) { Text("Enable guard") }
                    TextButton(onClick = {
                        app.autohost.capabilities.screen.ScreenGuardService.stop(graph.appContext)
                    }) { Text("Disable guard") }
                }
            }
        }
        SectionHeader("Surfaces")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Bubble, tiles, shortcuts, widget, external intent, inspector, volume-key stop", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Bind AutoHost 1–5 tiles to tasks, set the bubble mode, show or rotate the " +
                        "external-trigger token and arm the hold-to-stop gesture.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onOpenSurfaces, modifier = Modifier.padding(top = 8.dp)) { Text("Open") }
            }
        }
        SectionHeader("Shell tier (advanced)")
        ShellTierSection(graph)

        SectionHeader("Developer & security")
        // P8-13: the insecure-feature switches (plan §14.5) — every high-risk capability family
        // needs BOTH its per-task grant and the global switch here, off by default.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                        Text("Relaxed mode", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Temporarily enable every switch below except the explicit denies. " +
                                "Off by default; denies always win.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = switchState.relaxed,
                        onCheckedChange = { on ->
                            switchState = switchState.copy(relaxed = on)
                            scope.launch {
                                graph.insecureSwitches.setRelaxed(on)
                                switchState = graph.insecureSwitches.state
                            }
                        },
                    )
                }
                Text(
                    "These switches unlock high-risk capability families. A task still needs its own " +
                        "grant for the capability — the switch only lifts the global lock.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                for (feature in app.autohost.core.broker.security.InsecureFeatures.KNOWN) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                            Text(feature, style = MaterialTheme.typography.bodyMedium)
                            Text(switchHint(feature), style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(
                            checked = feature in switchState.enabled || (switchState.relaxed && feature !in switchState.denied),
                            onCheckedChange = { on ->
                                scope.launch {
                                    graph.insecureSwitches.setFeature(feature, on)
                                    switchState = graph.insecureSwitches.state
                                }
                            },
                        )
                    }
                }
            }
        }
        SectionHeader("Developer")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.padding(end = 8.dp).weight(1f)) {
                        Text("Dev API", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Lets a PC push, run and inspect tasks over 127.0.0.1. Off by default; token required.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = devApiOn, onCheckedChange = { on ->
                        devApiOn = on
                        graph.devApiEnabled = on
                        // P8: the policy object is the server's source of truth now — flip it here.
                        graph.devApiPolicy.enabled = on
                        if (on) {
                            token = graph.devApiTokens.issue()
                            graph.devApiPolicy.allowRemote = graph.switchEnabled("dev_api_remote") == true
                            graph.devApiServer.start()
                        } else {
                            graph.devApiServer.stop()
                            graph.devApiTokens.revoke()
                            token = ""
                        }
                    })
                }
                if (devApiOn && token.isNotEmpty()) {
                    Text("Port: 8080 (adb forward tcp:8080 tcp:8080)", style = MaterialTheme.typography.labelSmall)
                    Text("Token: ${token.take(8)}…", style = MaterialTheme.typography.labelSmall)
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(token)) }) { Text("Copy token") }
                        TextButton(onClick = {
                            token = graph.devApiTokens.issue()
                        }) { Text("Rotate") }
                    }
                    // P11-07 (ADR-081): the reverse transport — the phone dials the PC bridge and
                    // serves the SAME token-gated JSON-RPC surface over it (no USB/adb forward).
                    ReverseBridgeRow(graph)
                }
            }
        }
        SectionHeader("Webhooks")
        WebhookSection(graph)

        SectionHeader("Backup & packs")
        BackupAndPacksSection(graph)
        SectionHeader("About")
        Text("AutoHost — local automation host. No telemetry, no cloud.", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * S26 (P11-08/P11-04): Settings → Backup & packs — the SAF export/restore round trip (tasks +
 * profiles, minus secrets: profiles come back with EMPTY secrets and the plan says so) and the
 * `.aapack` install/remove list (ADR-080).
 */
@Composable
private fun BackupAndPacksSection(graph: AppGraph) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember { mutableStateOf("Nothing exported or restored yet.") }
    var packs by remember { mutableStateOf(graph.packController.list()) }
    var overwrite by remember { mutableStateOf(false) }

    val createBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            graph.backupController.export()
                .onSuccess { json ->
                    graph.app.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.encodeToByteArray())
                    }
                    status = "backup written ($uri)"
                }
                .onFailure { status = "export failed: ${it.message}" }
        }
    }
    val openBackup = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                graph.app.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (text == null) {
                status = "could not read $uri"
            } else {
                graph.backupController.restore(text, allowOverwrite = overwrite)
                    .onSuccess { r ->
                        status = "restored ${r.tasks} task(s), ${r.profiles} profile(s), skipped ${r.skipped}" +
                            " — re-enter profile secrets (names in the plan)"
                    }
                    .onFailure { status = "restore failed: ${it.message}" }
            }
        }
    }
    val openPack = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val outcome = graph.packController.install(uri.toString()) {
                graph.app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("unreadable $uri")
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                packs = graph.packController.list()
                status = when (outcome) {
                    is app.autohost.packs.PackController.InstallOutcome.Installed ->
                        "pack ${outcome.pack.id}@${outcome.pack.version} installed"
                    is app.autohost.packs.PackController.InstallOutcome.Rejected ->
                        "pack rejected: ${outcome.issues.joinToString { it.message }}"
                }
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Backup (tasks + profiles, minus secrets)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { createBackup.launch("autohost-backup.json") }) { Text("Export") }
                TextButton(onClick = { openBackup.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Restore…") }
            }
            Row {
                Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                Text(
                    "Overwrite installed tasks on restore",
                    modifier = Modifier.padding(start = 8.dp).padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("App packs (.aapack)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Named selectors, screens, labels and tips per target app — referenced from tasks via uses_packs and injected as config.packs each run.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openPack.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("Install pack…") }
            }
            for (pack in packs) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${pack.id} @ ${pack.version} → ${pack.target}", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = {
                        graph.packController.remove(pack.id)
                        packs = graph.packController.list()
                    }) { Text("Remove") }
                }
            }

            Text(status, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * S26a (P11-07, ADR-081): Settings → Developer → Reverse bridge — the phone dials the PC
 * (`autohost bridge` listener) and the CLI talks to it like the localhost server. The URL lives
 * in memory (re-entered per session) — it is a LAN address, not a secret, but it is not app
 * config either: the connection is opt-in per session, which is the honest default for a
 * device-initiated socket.
 */
@Composable
private fun ReverseBridgeRow(graph: AppGraph) {
    var url by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(graph.reverseLink.isRunning) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.weight(1f),
            label = { Text("Reverse bridge (ws://pc-host:8090)") },
            singleLine = true,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            graph.reverseLink.start(url)
            connected = graph.reverseLink.isRunning
        }) { Text("Connect") }
        TextButton(onClick = {
            graph.reverseLink.stop()
            connected = false
        }) { Text("Stop") }
    }
    val state = graph.reverseLink.state
    val error = graph.reverseLink.lastError
    Text(
        when {
            error != null && state == "error" -> "bridge error: $error"
            else -> "state: $state"
        },
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * S23 (P8-08): Settings → Webhooks — the `net.server` endpoint that fires `external` triggers.
 * Localhost by default; the LAN toggle still requires the token (plan §6.1 catalog).
 */
@Composable
private fun WebhookSection(graph: AppGraph) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hookOn by remember { mutableStateOf(graph.webhooks.enabled()) }
    var hookToken by remember { mutableStateOf(graph.webhooks.token()) }
    var hookLan by remember { mutableStateOf(graph.webhooks.lan()) }
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Webhook endpoint", style = MaterialTheme.typography.titleSmall)
            Text(
                "POST http://127.0.0.1:8082/hook/<name> with header X-AutoHost-Token fires the " +
                    "matching external trigger rule. Hook names come from the task's trigger rules.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Enabled", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = hookOn, onCheckedChange = { on ->
                    hookOn = on
                    scope.launch {
                        graph.data.settings.setBool(app.autohost.webhook.WebhookController.SETTING_ENABLED, on)
                        graph.webhooks.apply(on, graph.webhooks.port(), hookToken, hookLan, "settings")
                    }
                })
            }
            if (hookOn) {
                Text("Token: ${hookToken.take(8)}…", style = MaterialTheme.typography.labelSmall)
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(hookToken)) }) { Text("Copy token") }
                    TextButton(onClick = {
                        val fresh = app.autohost.core.common.Ids.ulid(System.currentTimeMillis())
                        hookToken = fresh
                        scope.launch {
                            graph.data.settings.setString(app.autohost.webhook.WebhookController.SETTING_TOKEN, fresh)
                            graph.webhooks.apply(true, graph.webhooks.port(), fresh, hookLan, "rotate")
                        }
                    }) { Text("Rotate") }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("Reachable from the LAN (token still required)", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = hookLan, onCheckedChange = { on ->
                        hookLan = on
                        scope.launch {
                            graph.data.settings.setBool(app.autohost.webhook.WebhookController.SETTING_LAN, on)
                            graph.webhooks.apply(true, graph.webhooks.port(), hookToken, on, "lan-toggle")
                        }
                    })
                }
            }
        }
    }
}

/** One-line meaning per switch (plan §14.5: plain-language meaning in the review/UI). */
private fun switchHint(feature: String): String = when (feature) {
    "shell" -> "device.shell — allowlisted shell commands over the paired shell tier"
    "raw_api" -> "android.api.direct — raw Android API access from Python scripts"
    "app_manage" -> "device.app manage — install/clear/grant/revoke (Phase 9+)"
    "all_files" -> "files.all — broad shared-storage access"
    "sms" -> "device.sms — read and send SMS"
    "telephony" -> "device.telephony — call state, place calls"
    "logcat" -> "device.logcat — tail system log for evidence"
    "mock_location" -> "device.location.mock — mock location provider"
    "dev_api_remote" -> "Dev API reachable from the LAN, not only localhost"
    else -> ""
}

/**
 * P9-12: the shell pairing guide (plan: "guided steps + post-reboot reminder"; UI_SPEC S24). The
 * transport status chips read the live [ShellTransports] statuses; the guide spells out the
 * Android 10 path (PC-assisted `adb tcpip 5555` per boot) and the one-time WRITE_SECURE_SETTINGS
 * grant. This is where OPEN_SHELL_SETUP fix actions land.
 */
@Composable
private fun ShellTierSection(graph: AppGraph) {
    val clipboard = LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var statuses by remember {
        mutableStateOf(graph.shellTransportStatuses())
    }
    var uiAutomationOn by remember { mutableStateOf(graph.uiAutomationToggle) }
    var refreshing by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Pair the shell tier", style = MaterialTheme.typography.titleSmall)
            Text(
                "Unlocks device.shell, app manage, logcat, radios and display control. The " +
                    "ADB-over-TCP path resets at every reboot on Android 10 — re-arm from the PC " +
                    "after each boot (AutoHost reminds you).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            // Live transport chips (adb-tcp → wireless → shizuku → root).
            statuses.forEach { row ->
                val (label, state, reason) = row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = state + (reason?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            state == "live" -> MaterialTheme.colorScheme.primary
                            state == "awaiting_authorization" -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = {
                    refreshing = true
                    scope.launch {
                        graph.refreshShellTransports()
                        statuses = graph.shellTransportStatuses()
                        refreshing = false
                    }
                }) { Text(if (refreshing) "Checking…" else "Check now") }
                TextButton(onClick = {
                    clipboard.setText(
                        AnnotatedString("adb tcpip 5555 && adb connect <phone-ip>:5555"),
                    )
                }) { Text("Copy adb steps") }
            }
            Text(
                "One-time PC grants (optional, no root):",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "adb shell pm grant app.autohost android.permission.WRITE_SECURE_SETTINGS — " +
                    "enables settings writes and accessibility self-heal without the shell tier. " +
                    "The CLI prints this: autohost grant-secure-settings",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Experimental: UiAutomation UI link", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Exclusive with accessibility — enabling this closes the accessibility " +
                            "session while the UiAutomation link is in use (they can never run " +
                            "together). Off by default.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = uiAutomationOn, onCheckedChange = { on ->
                    uiAutomationOn = on
                    graph.setUiAutomationEnabled(on)
                })
            }
        }
    }
}
