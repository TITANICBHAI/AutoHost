package ai.closepaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadDrillDown
import ai.closepaw.ui.theme.closePaw

@Composable
internal fun AgentBehaviorSettingsPage(
    perceptionMode: String,
    onPerceptionModeChange: (String) -> Unit,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    onPlatformModeChange: (PlatformMode) -> Unit,
    browserScriptEnabled: Boolean,
    onBrowserScriptEnabledChange: (Boolean) -> Unit,
    approvalMode: ApprovalMode,
    onApprovalModeChange: (ApprovalMode) -> Unit,
    onNavigateToAppAccess: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    isSessionRunning: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(title = "Agent Behavior", onBack = onBack, onClose = onClose)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg)
        ) {
            ApprovalSection(
                approvalMode = approvalMode,
                onApprovalModeChange = onApprovalModeChange,
                onNavigateToAppAccess = onNavigateToAppAccess,
            )
            Spacer(modifier = Modifier.height(20.dp))
            SettingsSection(title = "Perception") {
                PerceptionModeSelector(selectedMode = perceptionMode, onModeChange = onPerceptionModeChange)
            }
            Spacer(modifier = Modifier.height(20.dp))
            DisplaySection(
                persistedMode = platformMode,
                effectiveMode = effectivePlatformMode,
                onPlatformModeChange = onPlatformModeChange,
            )
            Spacer(modifier = Modifier.height(20.dp))
            ToolsSection(
                browserScriptEnabled = browserScriptEnabled,
                onBrowserScriptEnabledChange = onBrowserScriptEnabledChange,
                isSessionRunning = isSessionRunning,
            )
            Fleuron()
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private data class ApprovalModeOption(
    val mode: ApprovalMode,
    val label: String,
    val description: String,
)

private val APPROVAL_MODE_OPTIONS = listOf(
    ApprovalModeOption(
        mode = ApprovalMode.SMART,
        label = "Per-App",
        description = "Ask before risky actions, based on each app's access rules",
    ),
    ApprovalModeOption(
        mode = ApprovalMode.AUTO_APPROVE,
        label = "Auto-Approve",
        description = "Run allowed actions without asking. Rejected apps stay blocked.",
    ),
)

@Composable
private fun ApprovalSection(
    approvalMode: ApprovalMode,
    onApprovalModeChange: (ApprovalMode) -> Unit,
    onNavigateToAppAccess: () -> Unit,
) {
    SettingsSection(title = "Approval") {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(MaterialTheme.closePaw.spacing.cardPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    APPROVAL_MODE_OPTIONS.forEach { option ->
                        SegmentChip(
                            label = option.label,
                            isSelected = approvalMode == option.mode,
                            onClick = { onApprovalModeChange(option.mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val desc = APPROVAL_MODE_OPTIONS.find { it.mode == approvalMode }?.description ?: ""
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.closePaw.inkFaint,
                )
                if (approvalMode == ApprovalMode.SMART) {
                    Spacer(modifier = Modifier.height(MaterialTheme.closePaw.spacing.md))
                    SettingsNavigationRow(
                        title = "App Access Rules",
                        subtitle = "Set allow, ask, or reject for each app",
                        onClick = onNavigateToAppAccess,
                    )
                }
            }
        }
    }
}
