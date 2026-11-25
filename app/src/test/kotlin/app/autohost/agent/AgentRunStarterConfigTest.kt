package app.autohost.agent

import app.autohost.runtime.agent.AgentConfig
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** INT-E4b: what the agent's starter writes into a run's config is what the agent runtime reads back. */
class AgentRunStarterConfigTest {

    private val none = JsonObject(emptyMap())

    private fun written(tasks: List<AgentRunStarter.AllowedTask>): JsonObject =
        AgentRunStarter.configFor("Make a video", listOf("com.example.video"), "work", none, tasks)

    private fun agentPart(config: JsonObject): JsonObject = config["agent"] as JsonObject

    @Test fun `the value names ride with the run, per task`() {
        val read = AgentConfig.from(
            written(
                listOf(
                    AgentRunStarter.AllowedTask("daily.upload", "Daily upload", listOf("title", "description")),
                    AgentRunStarter.AllowedTask("plain.one", "Plain one"),
                ),
            ),
        )
        assertEquals(listOf("daily.upload", "plain.one"), read.tasks)
        assertEquals(mapOf("daily.upload" to "Daily upload", "plain.one" to "Plain one"), read.taskNames)
        assertEquals(mapOf("daily.upload" to listOf("title", "description")), read.taskInputs)
    }

    @Test fun `tasks that take no values write no value list`() {
        val config = written(listOf(AgentRunStarter.AllowedTask("plain.one", "Plain one")))
        assertFalse(agentPart(config).containsKey("task_inputs"))
        assertTrue(AgentConfig.from(config).taskInputs.isEmpty())
    }

    @Test fun `with no task nothing about tasks is written`() {
        val agent = agentPart(written(emptyList()))
        assertFalse(agent.containsKey("tasks"))
        assertFalse(agent.containsKey("task_names"))
        assertFalse(agent.containsKey("task_inputs"))
    }

    @Test fun `the goal, the apps and the model are written as before`() {
        val read = AgentConfig.from(written(emptyList()))
        assertEquals("Make a video", read.goal)
        assertEquals(listOf("com.example.video"), read.packages)
        assertEquals("work", read.provider)
    }
}
