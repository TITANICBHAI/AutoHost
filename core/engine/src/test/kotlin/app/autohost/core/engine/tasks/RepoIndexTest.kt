package app.autohost.core.engine.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11-05 (written; awaiting Gradle): the repository index — parse validation, duplicate
 * handling, the select verdicts against installed manifests, and the sha256 integrity rule.
 */
class RepoIndexTest {

    private fun entryJson(
        id: String = "app.example.task",
        version: String = "1.2.0",
        source: String = "https://example.com/tasks/example.aatask",
        extra: String = "",
    ): String = """{"id":"$id","version":"$version","source":"$source"$extra}"""

    @Test
    fun `a full document parses into typed entries`() {
        val text = """{"schema":1,"name":"Example repo","updated_at":"2026-10-26T00:00:00Z","tasks":[""" +
            entryJson(extra = ""","title":"Example","runtime":"python","sha256":"${"a".repeat(64)}","min_host_api":1,"notes":"hi"}""") +
            """]}"""
        val result = RepoIndex.parse(text)
        assertTrue(result.ok)
        val entry = result.index!!.entries.single()
        assertEquals("app.example.task", entry.id)
        assertEquals("1.2.0", entry.version)
        assertEquals("python", entry.runtime)
        assertEquals(1, entry.minHostApi)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `missing required fields produce per-entry issues and drop the entry`() {
        val result = RepoIndex.parse("""{"schema":1,"tasks":[{"title":"no id/version/source"}]}""")
        assertTrue(result.index!!.entries.isEmpty())
        val paths = result.issues.map { it.path + ":" + it.code }
        assertTrue(paths.contains("/tasks/0/id:REQUIRED"))
        assertTrue(paths.contains("/tasks/0/version:REQUIRED"))
        assertTrue(paths.contains("/tasks/0/source:REQUIRED"))
    }

    @Test
    fun `bad semver bad sha and unknown keys are rejected`() {
        val text = """{"schema":1,"tasks":[""" +
            entryJson(version = "1.2") + "," +
            entryJson(id = "app.example.two", extra = ""","sha256":"abc"""") + "," +
            entryJson(id = "app.example.three", extra = ""","surprise":true""") +
            """]}"""
        val result = RepoIndex.parse(text)
        val codes = result.issues.map { it.code }
        assertTrue(codes.contains("PATTERN"))
        assertTrue(codes.contains("UNKNOWN_KEY"))
        assertTrue(result.index!!.entries.isEmpty())
    }

    @Test
    fun `duplicate ids keep the first occurrence`() {
        val text = """{"schema":1,"tasks":[${entryJson(version = "1.0.0")},${entryJson(version = "2.0.0")}]}"""
        val result = RepoIndex.parse(text)
        assertEquals(1, result.index!!.entries.size)
        assertEquals("1.0.0", result.index!!.entries.single().version)
        assertTrue(result.issues.any { it.code == "DUP_ID" })
    }

    @Test
    fun `a malformed root yields no index with a typed issue`() {
        assertNull(RepoIndex.parse("[]").index)
        assertNull(RepoIndex.parse("not json").index)
        val noTasks = RepoIndex.parse("""{"schema":1}""")
        assertNull(noTasks.index)
        assertTrue(noTasks.issues.any { it.path == "/tasks" && it.code == "REQUIRED" })
    }

    @Test
    fun `select offers Install Upgrade UpToDate and rejects downgrades`() {
        val entry = RepoIndex.parse("""{"schema":1,"tasks":[${entryJson(version = "1.3.0")}]}""")
            .index!!.entries.single()
        assertTrue(RepoIndex.select(entry, installedVersion = null, hostApi = 1) is RepoIndex.Verdict.Install)
        assertEquals(
            RepoIndex.Verdict.Upgrade("1.2.0", "1.3.0"),
            RepoIndex.select(entry, installedVersion = "1.2.0", hostApi = 1),
        )
        assertTrue(RepoIndex.select(entry, installedVersion = "1.3.0", hostApi = 1) is RepoIndex.Verdict.UpToDate)
        val older = RepoIndex.select(entry, installedVersion = "2.0.0", hostApi = 1)
        assertEquals("REPO_OLDER", (older as RepoIndex.Verdict.Reject).reason)
    }

    @Test
    fun `select rejects entries above the host api`() {
        val text = """{"schema":1,"tasks":[${entryJson(extra = ""","min_host_api":2""")}]}"""
        val entry = RepoIndex.parse(text).index!!.entries.single()
        val verdict = RepoIndex.select(entry, installedVersion = null, hostApi = 1)
        assertEquals("HOST_TOO_OLD", (verdict as RepoIndex.Verdict.Reject).reason)
    }

    @Test
    fun `sha verification is exact hex and case-insensitive`() {
        val expected = "A".repeat(64)
        assertTrue(RepoIndex.verifySha(expected, "a".repeat(64)))
        assertFalse(RepoIndex.verifySha(expected, "b".repeat(64)))
        assertFalse("wrong length refused", RepoIndex.verifySha(expected.drop(1), "a".repeat(63)))
        assertFalse("non-hex refused", RepoIndex.verifySha("z".repeat(64), "z".repeat(64)))
    }
}
