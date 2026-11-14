package app.autohost.ui2

import app.autohost.AppGraph
import app.autohost.ai.AppProfileStore
import kotlinx.coroutines.flow.first

/** Reads the facts the first-run checklist needs that are not Android switches. Call off the main thread. */
object SetupFactsReader {
    suspend fun read(graph: AppGraph): SetupFacts {
        val enabled = graph.data.settings.bool(AppProfileStore.SETTING_ENABLED)
        val profiles = AppProfileStore(graph.data.settings, graph.secrets, graph.secretRegistry).load()
        val hasTasks = graph.data.tasks.observeActive().first().isNotEmpty() || graph.devTasks.tasks.value.isNotEmpty()
        return SetupFacts(aiConfigured = enabled && profiles.isNotEmpty(), hasTasks = hasTasks, onboarded = graph.onboarded.value)
    }
}
