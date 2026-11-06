package app.autohost.ui.agent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.theme.Tokens

/**
 * P14-05/S31 completion (R4): the `.aarun` agent-section VIEWER surface. Two entry paths:
 *
 *  - **the just-exported run**: RunScreen hands the exported bundle's bytes in — no picker;
 *  - **an external bundle**: the import button opens the system document picker
 *    (`ActivityResultContracts.GetContent`, zip) and feeds the bytes to the same state.
 *
 * All display rules live in [AgentSectionViewerState] (JVM-tested); the redaction is the same
 * one the export applied — the viewer can never be less redacted than the bundle.
 */
@Composable
fun AgentBundleViewer(
    state: AgentSectionViewerState,
    modifier: Modifier = Modifier,
    initialBundleBytes: ByteArray? = null,
) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(initialBundleBytes != null && state.load(initialBundleBytes)) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            loadError = "the bundle could not be read"
            loaded = false
        } else {
            loaded = state.load(bytes)
            loadError = if (loaded) null else "no agent section in that bundle"
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Agent run bundle viewer" },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiiDp[1].dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(Tokens.spacingDp[2].dp)) {
            OutlinedButton(onClick = { picker.launch("application/zip") }) {
                Text("import a .aarun bundle")
            }
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val section = state.section
            if (loaded && section != null) {
                HorizontalDivider(Modifier.padding(vertical = Tokens.spacingDp[1].dp))
                Text("Agent section", style = MaterialTheme.typography.labelLarge)
                section.goal.take(120).let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Tokens.spacingDp[0].dp))
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.spacingDp[1].dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    UsageStat("turns", section.usage.turns.toString())
                    UsageStat("calls", section.usage.modelCalls.toString())
                    UsageStat("actions", section.usage.actions.toString())
                    UsageStat("tokens", section.usage.tokens.toString())
                }
                Text(
                    "trace: kept ${section.traceKept}, dropped ${section.traceDropped} (visible counter)",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = Tokens.spacingDp[1].dp),
                )
                section.stopReason?.let { reason ->
                    Text(
                        "stop: ${section.stopStatus ?: "FAILED"} — $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Tokens.spacingDp[0].dp),
                    )
                }
                if (section.transcript.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = Tokens.spacingDp[1].dp))
                    for (line in section.transcript) {
                        Text(
                            "• ${line.kind}: ${line.text.take(120)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = Tokens.spacingDp[0].dp),
                        )
                    }
                } else {
                    Text(
                        "no transcript in the bundle (keep_transcript was off)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.spacingDp[1].dp),
                    )
                }
            }
        }
    }
}
