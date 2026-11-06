package app.autohost.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.autohost.core.designsystem.theme.Tokens

/**
 * P14-04 (S30, UI_SPEC §10): the agent run detail — the thought → action timeline (status icons
 * from the paired `agent.result`), the budgets/usage panel, and the host verdict banner. All
 * rules live in [AgentRunDetailState] (JVM-tested); this composable renders a snapshot and
 * honours the token contracts (4 dp grid, radii, TalkBack labels — P14-09). The events were
 * redacted at the source (P13-15) — nothing secret can appear here by construction.
 */
@Composable
fun AgentRunDetail(
    state: AgentRunDetailState,
    modifier: Modifier = Modifier,
) {
    val snapshot = state.snapshot()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                    contentDescription = "Agent run detail"
                    liveRegion = LiveRegionMode.Polite // new timeline entries are announced
                },
        shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiiDp[1].dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(Tokens.spacingDp[2].dp)) {
            Text("Agent run", style = MaterialTheme.typography.labelLarge)

            // the budgets/usage panel (S30: turns, model calls, tokens, actions, wall clock)
            Row(
                Modifier.fillMaxWidth().padding(top = Tokens.spacingDp[1].dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                UsageStat("turns", snapshot.usage.turns.toString())
                UsageStat("calls", snapshot.usage.modelCalls.toString())
                UsageStat("tokens", snapshot.usage.tokens.toString())
                UsageStat("actions", snapshot.usage.actions.toString())
                UsageStat("time", formatWallClock(snapshot.wallClockMs))
            }

            for (warning in snapshot.budgetWarnings) {
                Text(
                    "budget: 80% of $warning used",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Tokens.spacingDp[0].dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = Tokens.spacingDp[1].dp))

            // the thought → action timeline
            for (entry in snapshot.entries) {
                when (entry) {
                    is AgentRunDetailState.Entry.Thought -> Text(
                        "• ${entry.text}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = Tokens.spacingDp[0].dp),
                    )

                    is AgentRunDetailState.Entry.Action -> Row(
                        Modifier.fillMaxWidth().padding(top = Tokens.spacingDp[0].dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "▸ ${entry.tool}${if (entry.argsSummary.isBlank()) "" else " ${entry.argsSummary.take(60)}"}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            when (entry.ok) {
                                true -> "✓"
                                false -> "✗"
                                null -> "…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (entry.ok) {
                                true -> MaterialTheme.colorScheme.primary
                                false -> MaterialTheme.colorScheme.error
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // the host verdict banner — the model's claim is evidence, never the verdict
            snapshot.verdict?.let { verdict ->
                val (label, ok) = when (verdict) {
                    AgentRunDetailState.Verdict.Confirmed -> "Host verdict: confirmed by you" to true
                    AgentRunDetailState.Verdict.Rejected -> "Host verdict: you rejected the result" to false
                    AgentRunDetailState.Verdict.Unverified -> "Host verdict: never confirmed" to false
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Tokens.spacingDp[1].dp),
                )
            }
            snapshot.status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Tokens.spacingDp[0].dp))
            }
        }
    }
}

@Composable
private fun UsageStat(label: String, value: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.labelLarge)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatWallClock(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
    ms >= 1_000 -> "${ms / 1_000}s"
    else -> "${ms}ms"
}
