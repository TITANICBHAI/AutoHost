package app.autohost.ui.capsule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.theme.Tokens

/**
 * P14-03 (S29, ADR-094): the approval sheet for a risky tool call. The three choices come from
 * [ApprovalState.CHOICES] — the same list the notification actions render — and the decision
 * rules (timeout → deny and stop) live in [ApprovalState], not here. CAUTIOUS apps show no node
 * label (AGENT_RUNTIME §9). All touch targets honour the 48 dp token (P14-09).
 */
@Composable
fun ApprovalSheet(
    state: ApprovalState,
    onDecision: (callId: String, decision: ApprovalState.Decision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ask = state.pending ?: return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Approval needed: ${ask.method} in ${ask.targetPackage}"
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(Tokens.spacingDp[3].dp)) {
            Text(
                "Allow ${ask.method}?",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                buildString {
                    append(ask.capability)
                    append(" → ")
                    append(ask.targetPackage)
                },
                style = MaterialTheme.typography.bodySmall,
            )
            ask.label?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Tokens.spacingDp[1].dp),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Tokens.spacingDp[2].dp),
                horizontalArrangement = Arrangement.spacedBy(Tokens.spacingDp[1].dp),
            ) {
                TextButton(
                    onClick = { onDecision(ask.callId, ApprovalState.Decision.DENY) },
                    modifier = Modifier.heightIn(min = Tokens.MIN_TOUCH_TARGET_DP.dp),
                ) { Text(ApprovalState.CHOICE_DENY) }
                OutlinedButton(
                    onClick = { onDecision(ask.callId, ApprovalState.Decision.ALLOW_FOR_RUN) },
                    modifier = Modifier.heightIn(min = Tokens.MIN_TOUCH_TARGET_DP.dp),
                ) { Text(ApprovalState.CHOICE_ALLOW_FOR_RUN) }
                OutlinedButton(
                    onClick = { onDecision(ask.callId, ApprovalState.Decision.ALLOW_ONCE) },
                    modifier = Modifier.heightIn(min = Tokens.MIN_TOUCH_TARGET_DP.dp),
                ) { Text(ApprovalState.CHOICE_ALLOW_ONCE) }
            }
        }
    }
}
