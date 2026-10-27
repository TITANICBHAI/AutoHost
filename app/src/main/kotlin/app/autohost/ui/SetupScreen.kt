package app.autohost.ui

import androidx.compose.runtime.setValue
import app.autohost.Routes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.autohost.AppGraph
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.R
import app.autohost.core.designsystem.components.AvailabilityChip
import app.autohost.core.designsystem.components.SectionHeader
import app.autohost.core.model.Availability
import kotlinx.coroutines.launch

/** Setup screen (wave 1): accessibility service status + deep link, notifications permission. */
@Composable
fun SetupScreen(activity: MainActivity, onNavigate: (Routes, String?) -> Unit = { _, _ -> }) {
    val a11yService by app.autohost.capabilities.a11y.A11yBridge.service.collectAsState()
    val a11yAvailability: Availability = app.autohost.capabilities.a11y.A11yAvailability.current(a11yService != null)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        SectionHeader("REQUIRED")
        Card {
            Column(Modifier.padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxSize()) {
                    Text(
                        if (a11yService != null) stringResource(R.string.setup_a11y_on)
                        else stringResource(R.string.setup_a11y_off),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    AvailabilityChip(a11yAvailability)
                }
                Text(
                    stringResource(R.string.setup_a11y_off_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (a11yService == null) {
                    Button(
                        onClick = { activity.startActivity(AppGraph.accessibilitySettingsIntent()) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.setup_a11y_fix))
                    }
                }
            }
        }
        SectionHeader("ALSO USEFUL")
        Card {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.setup_notifications), style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName)
                        activity.startActivity(intent)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Open notification settings")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { onNavigate(Routes.HEALTH, null) }) { Text("Setup & Health") }
            OutlinedButton(onClick = { onNavigate(Routes.SETTINGS, null) }) { Text("Settings") }
        }
        AlwaysOnChecklist(activity)
    }
}

/**
 * P4-13: the always-on automation device recipe (plan §3.4) as a visible checklist. Every row
 * says what it is for and how to fix it; the secure-settings self-heal toggle (P4-14) lives here
 * because it needs the same plain-language honesty.
 */
@Composable
private fun AlwaysOnChecklist(activity: MainActivity) {
    val graph = (activity.application as AutoHostApp).graph
    SectionHeader("ALWAYS-ON RECIPE")
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "For reliable recurring automation: a dedicated (or semi-dedicated) phone on a charger.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChecklistRow(
                title = "Stay awake while charging",
                body = "Developer options → Stay awake. Keeps the screen available for scheduled tasks.",
            )
            ChecklistRow(
                title = "Lock screen None or Swipe",
                body = "Non-secure locks can be dismissed automatically; PIN/pattern waits for you (by design).",
            )
            ChecklistRow(
                title = "Battery → Unrestricted",
                body = "Prevents the OEM from pausing background runs (plus the OEM autostart setting where present).",
            )
            ChecklistRow(
                title = "Wi-Fi on during sleep",
                body = "Network conditions and HTTP tasks need it while the screen is off.",
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Boot self-heal", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "After a reboot, re-enable the accessibility service automatically. Needs the one-time " +
                            "WRITE_SECURE_SETTINGS grant (PC: pm grant … android.permission.WRITE_SECURE_SETTINGS).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                var selfHealOn by androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    selfHealOn = graph.data.settings.bool(app.autohost.selfheal.SecureSettingsSelfHeal.SETTING_KEY)
                }
                Switch(
                    checked = selfHealOn,
                    onCheckedChange = { on ->
                        selfHealOn = on
                        graph.appScope.launch {
                            graph.data.settings.setBool(app.autohost.selfheal.SecureSettingsSelfHeal.SETTING_KEY, on)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ChecklistRow(title: String, body: String) {
    Column {
        Text("• $title", style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
