package app.autohost.ui.capsule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.theme.Tokens

/**
 * P14-02 (S28, ADR-094): the one run capsule, used in-app and as the overlay. All display rules
 * live in [RunCapsuleState] (tested); this composable renders the mode and honours the token
 * contracts (4 dp grid, radii, reduced motion, 48 dp touch targets, TalkBack labels — P14-09).
 * Stop is visible in every running state; "Stopping…" shows immediately via the state holder.
 */
@Composable
fun RunCapsule(
    state: RunCapsuleState,
    onStop: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val mode = state.mode) {
        is RunCapsuleState.Mode.Hidden -> Unit // renders nothing; claims no touches

        is RunCapsuleState.Mode.Running -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Run running: ${mode.taskName}" },
            shape = RoundedCornerShape(Tokens.radiiDp[1].dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(Tokens.spacingDp[2].dp)) {
                Text(mode.taskName, style = MaterialTheme.typography.labelLarge)
                mode.stage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                mode.agentTurns?.let {
                    Text("turn $it", style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Tokens.spacingDp[0].dp))
                Row(
                    Modifier.fillMaxWidth().padding(top = Tokens.spacingDp[1].dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.stopVisible) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.heightIn(min = Tokens.MIN_TOUCH_TARGET_DP.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) { Text("Stop") }
                    }
                }
            }
        }

        is RunCapsuleState.Mode.Stopping -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Run stopping: ${mode.taskName}" },
            shape = RoundedCornerShape(Tokens.radiiDp[1].dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                "Stopping…",
                Modifier.padding(Tokens.spacingDp[2].dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        is RunCapsuleState.Mode.Done -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Run done: ${mode.taskName}" },
            shape = RoundedCornerShape(Tokens.radiiDp[1].dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                "Done — ${mode.taskName}",
                Modifier.padding(Tokens.spacingDp[2].dp),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        is RunCapsuleState.Mode.Error -> Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Run failed: ${mode.taskName}" },
            shape = RoundedCornerShape(Tokens.radiiDp[1].dp),
            color = MaterialTheme.colorScheme.errorContainer,
        ) {
            Column(Modifier.padding(Tokens.spacingDp[2].dp)) {
                Text(mode.message, style = MaterialTheme.typography.labelLarge)
                mode.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismissError,
                        modifier = Modifier.heightIn(min = Tokens.MIN_TOUCH_TARGET_DP.dp),
                    ) { Text("Dismiss") }
                }
            }
        }
    }
}
