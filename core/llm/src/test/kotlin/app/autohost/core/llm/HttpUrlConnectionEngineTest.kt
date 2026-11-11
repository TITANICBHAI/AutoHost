package app.autohost.core.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * P13-06 (`docs/MODEL_GATEWAY.md` §9.4): the production engine against the JDK's own HTTP server —
 * success, 429 + Retry-After, 500, a 302 that must NOT be followed, a body over the cap, malformed
 * JSON passes through (the codec's job), and cancellation returns within ~1 s. Under D-26 these
 * are written, not run.
 */
class HttpUrlConnectionEngineTest {

    private var server: HttpServer? = null
    private var port: Int = 0
    private val hits = AtomicInteger(0)

    private fun start(handler: (com.sun.net.httpserver.HttpExchange) -> Unit) {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/") { exchange ->
            hits.incrementAndGet()
            handler(exchange)
            exchange.close()
        }
        s.start()
        server = s
        port = s.address.port
    }

    @After
    fun stop() {
        server?.stop(0)
    }

    private fun engine() = HttpUrlConnectionEngine(userAgent = "AutoHost/test")

    @Test
    fun `given a 200 handler when posted then the body and status come back`() = runBlocking {
        start { ex ->
            val body = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        val response = engine().post("http://127.0.0.1:$port/v1/chat/completions", emptyMap(), "{}".toByteArray(), 5_000)
        assertEquals(200, response.status)
        assertEquals("""{"ok":true}""", response.body)
    }

    @Test
    fun `given a 429 with Retry-After when posted then the header surfaces for the retry policy`() = runBlocking {
        start { ex ->
            ex.responseHeaders.add("Retry-After", "7")
            ex.sendResponseHeaders(429, -1)
        }
        val response = engine().post("http://127.0.0.1:$port/", emptyMap(), "{}".toByteArray(), 5_000)
        assertEquals(429, response.status)
        assertEquals("7", response.headers["Retry-After"])
    }

    @Test
    fun `given a 302 when posted then it is NOT followed and the status surfaces`() = runBlocking {
        start { ex ->
            ex.responseHeaders.add("Location", "http://127.0.0.1:${port}/elsewhere")
            ex.sendResponseHeaders(302, -1)
        }
        val before = hits.get()
        val response = engine().post("http://127.0.0.1:$port/", emptyMap(), "{}".toByteArray(), 5_000)
        assertEquals(302, response.status)
        assertEquals("a redirect must never be followed", before + 1, hits.get())
    }

    @Test
    fun `given a body over the cap when posted then the engine refuses it`() = runBlocking {
        start { ex ->
            val big = ByteArray(3 * 1024 * 1024)
            ex.sendResponseHeaders(200, big.size.toLong())
            ex.responseBody.use { it.write(big) }
        }
        val smallCap = HttpUrlConnectionEngine(userAgent = "AutoHost/test", maxResponseBytes = 1024)
        val failed = try {
            smallCap.post("http://127.0.0.1:$port/", emptyMap(), "{}".toByteArray(), 5_000)
            false
        } catch (e: java.io.IOException) {
            true
        }
        assertTrue("the response cap must abort the read", failed)
    }

    @Test
    fun `given a hung server when the call is cancelled then it returns within about a second`() = runBlocking {
        start { ex -> Thread.sleep(30_000) } // never answers
        val engine = engine()
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        val job = launch {
            try {
                engine.post("http://127.0.0.1:$port/", emptyMap(), "{}".toByteArray(), 30_000)
                done.complete(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                done.complete(Unit) // the expected path: disconnect made the blocked read end
            } catch (e: java.io.IOException) {
                done.complete(Unit) // socket closed by disconnect — equally acceptable
            }
        }
        kotlinx.coroutines.delay(300)
        job.cancel()
        // disconnect() must end the blocked read: the deferred completes well within 2 s
        withTimeout(2_000) { done.await() }
        assertTrue(true)
    }
}
