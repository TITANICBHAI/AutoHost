package app.autohost

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.autohost.core.engine.orchestrator.StartRequest
import app.autohost.core.model.RunStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Maps instrumented tests to gate items (docs/CONVENTIONS.md testing layer 3). */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeviceGate(val gate: String)

/**
 * Phase-1 walking skeleton on hardware (docs/GATES.md G1-a/G1-e): a real Python task goes through the real
 * broker + worker process and succeeds; a broken task fails with a traceback reason. Requires the
 * accessibility service to be enabled for tasks that use it — the hello task below needs no capabilities.
 */
@RunWith(AndroidJUnit4::class)
@DeviceGate("G1-a")
class PythonRunEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun installDevTask(taskId: String, mainPy: String): Pair<File, File> {
        val dir = File(context.getExternalFilesDir("dev"), taskId)
        dir.mkdirs()
        File(dir, "task.json").writeText(
            """
            {
              "schema": 1,
              "id": "app.autohost.e2e.$taskId",
              "name": "$taskId",
              "runtime": {"type": "python", "entry": "main.py"},
              "needs": {"capabilities": []}
            }
            """.trimIndent(),
        )
        File(dir, "main.py").writeText(mainPy)
        return dir to File(dir, "main.py")
    }

    @Test
    fun givenHelloTaskWhenRunThenSucceededWithLogEvents() = runBlocking {
        val graph = AutoHostApp.of(context).graph
        val (dir, _) = installDevTask(
            "e2e-hello",
            """
            from autohost import log
            from autohost._ctx import Result
            def main():
                log.info("hello from the worker")
                return Result("HELLO", {"who": "e2e"})
            """.trimIndent(),
        )
        val manifest = graph.devTasks.also { it.refresh() }.tasks.value.first { it.taskId == "app.autohost.e2e.e2e-hello" }

        val record = graph.orchestrator.start(
            StartRequest(
                taskId = manifest.taskId,
                taskName = manifest.name,
                manifest = manifest.manifest,
                config = manifest.config,
                profile = JsonObject(emptyMap()),
                profileId = null,
                taskPath = manifest.dir.absolutePath,
                runDir = File(context.filesDir, "runs/e2e-hello-${System.currentTimeMillis()}").absolutePath,
            ),
        ).getOrNull()!!

        awaitTerminal(graph, record.runId, timeoutMs = 120_000)
        val run = graph.runStore.get(record.runId)!!
        assertEquals(RunStatus.SUCCEEDED, run.status)
        assertEquals("HELLO", run.result?.get("code")?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        assertTrue(graph.runStore.snapshot(record.runId).any { it.type == "log" })
        // cleanup
        dir.deleteRecursively()
    }

    @Test
    fun givenBrokenTaskWhenRunThenFailedWithTracebackReason() = runBlocking {
        val graph = AutoHostApp.of(context).graph
        val (dir, _) = installDevTask(
            "e2e-broken",
            """
            def main():
                raise ValueError("boom on purpose")
            """.trimIndent(),
        )
        val manifest = graph.devTasks.also { it.refresh() }.tasks.value.first { it.taskId == "app.autohost.e2e.e2e-broken" }

        val record = graph.orchestrator.start(
            StartRequest(
                taskId = manifest.taskId,
                taskName = manifest.name,
                manifest = manifest.manifest,
                config = manifest.config,
                profile = JsonObject(emptyMap()),
                profileId = null,
                taskPath = manifest.dir.absolutePath,
                runDir = File(context.filesDir, "runs/e2e-broken-${System.currentTimeMillis()}").absolutePath,
            ),
        ).getOrNull()!!

        awaitTerminal(graph, record.runId, timeoutMs = 120_000)
        val run = graph.runStore.get(record.runId)!!
        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("EXCEPTION", run.reason)
        assertTrue(run.message!!.contains("boom on purpose"))
        dir.deleteRecursively()
    }

    private suspend fun awaitTerminal(graph: AppGraph, runId: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val run = graph.runStore.get(runId)
            if (run != null && run.status.terminal) return
            kotlinx.coroutines.delay(250)
        }
        throw AssertionError("run $runId did not finish within ${timeoutMs}ms")
    }
}
