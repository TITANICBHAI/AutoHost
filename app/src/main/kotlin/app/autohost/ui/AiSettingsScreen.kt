package app.autohost.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.autohost.ai.AiSettingsState
import app.autohost.ai.AppProfileStore
import app.autohost.core.llm.ProviderProfile
import kotlinx.coroutines.launch

/**
 * The wiring host: builds the [AppProfileStore] over the app graph's settings + secrets, hydrates
 * the state once, and persists every change back (the S32 switches and the profile array).
 */
@Composable
fun AiSettingsHost(graph: app.autohost.AppGraph, onBack: () -> Unit) {
    val store = remember {
        AppProfileStore(graph.data.settings, graph.secrets, graph.secretRegistry)
    }
    val state = remember { AiSettingsState(keys = store, tester = app.autohost.ai.TestConnection.seam(store, graph)) }
    var model by remember { mutableStateOf(state.model) }

    // hydrate once; the graph carries the AI facts (fail-closed booleans until set here)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val profiles = store.load()
        state.hydrate(
            enabled = graph.data.settings.bool(AppProfileStore.SETTING_ENABLED),
            profiles = profiles,
            keepTranscripts = graph.data.settings.bool(AppProfileStore.SETTING_KEEP_TRANSCRIPTS),
            cleartextLoopback = graph.loopbackCleartextAllowed(), // X-14: the audited switch is the truth
            screenshots = profiles.associate {
                it.id to graph.data.settings.bool(AppProfileStore.settingScreenshots(it.id))
            },
            keyPresence = profiles.associate { it.id to store.hasApiKey(it.id) },
        )
        model = state.model
    }

    // persist on change (the store is the single source of truth; the state is the mirror)
    androidx.compose.runtime.LaunchedEffect(model) {
        graph.data.settings.setBool(AppProfileStore.SETTING_ENABLED, model.enabled)
        graph.data.settings.setBool(AppProfileStore.SETTING_KEEP_TRANSCRIPTS, model.keepTranscripts)
        // X-14: the loopback switch persists through the AUDITED InsecureFeatures machinery —
        // the screen toggle drives it; the enable/disable is audited by the existing path
        if (model.cleartextLoopback != graph.loopbackCleartextAllowed()) {
            graph.insecureSwitches.setFeature("ai_cleartext_loopback", model.cleartextLoopback)
        }
        store.save(model.profiles)
        for ((profileId, on) in model.screenshots) {
            graph.data.settings.setBool(AppProfileStore.settingScreenshots(profileId), on)
        }
        graph.refreshAiFacts(model.enabled, model.profiles)
    }

    AiSettingsScreen(state = state, model = model, onBack = onBack)
    androidx.compose.runtime.LaunchedEffect(state.model) { model = state.model }
}

/**
 * P14-06 (S32, UI_SPEC §10): Settings → AI features. The master switch (OFF by default) gates on
 * the data-egress disclosure; profiles are CRUD with the key entered ONCE and never shown again
 * (presence only); the screenshot and keep-transcripts switches are OFF by default; the
 * cleartext-loopback switch sits under advanced. All rules live in [AiSettingsState] (tested).
 */
@Composable
fun AiSettingsScreen(
    state: AiSettingsState,
    model: AiSettingsState.Model,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newLabel by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newModel by remember { mutableStateOf("") }
    var newKey by remember { mutableStateOf("") }
    var selectedProfile by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("AI features", style = MaterialTheme.typography.titleLarge)

        // the master switch — OFF by default; ON only through the disclosure
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI features on this device", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = model.enabled,
                onCheckedChange = { on -> state.setEnabled(on) },
            )
        }

        if (model.disclosurePending) {
            DisclosureCard(
                onAccept = { state.acceptDisclosure(System.currentTimeMillis()) },
                onDecline = { state.declineDisclosure() },
            )
        }

        HorizontalDivider()

        Text("Provider profiles", style = MaterialTheme.typography.titleMedium)
        for (profile in model.profiles) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${profile.model} @ ${profile.baseUrl}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            when {
                                model.keyPresent[profile.id] == true -> "key saved (never shown)"
                                else -> "no key entered"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { selectedProfile = if (selectedProfile == profile.id) null else profile.id }) {
                        Text(if (selectedProfile == profile.id) "close" else "edit")
                    }
                    OutlinedButton(onClick = { scope.launch { state.deleteProfile(profile.id) } }) {
                        Text("delete")
                    }
                }
                if (selectedProfile == profile.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("screenshots to this provider", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = model.screenshots[profile.id] == true,
                            onCheckedChange = { on -> state.setScreenshots(profile.id, on) },
                        )
                    }
                    OutlinedButton(onClick = { scope.launch { state.testConnection(profile.id) } }) {
                        Text("test connection")
                    }
                    model.lastTest[profile.id]?.let { result ->
                        Text(
                            (if (result.ok) "✓ " else "✗ ") + result.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    // the key entry: write-only — the field empties and the value is never read back
                    OutlinedTextField(
                        value = newKey,
                        onValueChange = { newKey = it },
                        label = { Text("API key (saved, never shown)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Button(onClick = {
                        if (newKey.isNotBlank()) {
                            scope.launch {
                                state.saveKey(profile.id, newKey)
                                newKey = "" // never echoed back
                            }
                        }
                    }) { Text("save key") }
                }
            }
            HorizontalDivider()
        }

        Text("Add a provider", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = newLabel, onValueChange = { newLabel = it }, label = { Text("label") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("base URL (https://…)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = newModel, onValueChange = { newModel = it }, label = { Text("model id") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(
            onClick = {
                val id = newLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                    .ifEmpty { "provider" }
                    .take(32)
                runCatching {
                    state.upsertProfile(
                        ProviderProfile(id = id, label = newLabel, baseUrl = newUrl.trim(), model = newModel.trim()),
                    )
                    newLabel = ""; newUrl = ""; newModel = ""
                }.onFailure { e -> state.reportError(e.message ?: "invalid profile") }
            },
            enabled = newLabel.isNotBlank() && newUrl.isNotBlank() && newModel.isNotBlank(),
        ) { Text("add profile") }
        model.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Transcripts", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("keep model transcripts (per run)", Modifier.weight(1f))
            Switch(checked = model.keepTranscripts, onCheckedChange = { state.setKeepTranscripts(it) })
        }

        HorizontalDivider()
        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("cleartext loopback (debug endpoints only)")
                Text(
                    "allows http:// to localhost / 127.0.0.1 / 10.0.2.2 for a PC stub model",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = model.cleartextLoopback, onCheckedChange = { state.setCleartextLoopback(it) })
        }

        OutlinedButton(onClick = onBack) { Text("back") }
    }
}

@Composable
private fun DisclosureCard(onAccept: () -> Unit, onDecline: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("What leaves your device", style = MaterialTheme.typography.titleMedium)
        Text(
            "Sent to the AI provider: your goal, the agent's instructions, the tool list, filtered " +
                "screen observations (element class, id, visible text up to 80 characters, state flags, " +
                "position and size, package names), tool results, and your answers to its questions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Never sent: screenshots (unless you enable them per provider), password text, API keys " +
                "or secrets, run tokens, clipboard, notification contents, your installed-app list, " +
                "and anything from a blocked app. Logs and audit carry sizes and counts, not content.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAccept) { Text("accept") }
            OutlinedButton(onClick = onDecline) { Text("decline") }
        }
    }
}
