package app.autohost

import app.autohost.core.broker.security.InsecureFeatures
import app.autohost.core.devapi.DevApiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * X-05 (Gate 11-e): the release-hardening checklist AS A TEST — the tree cannot silently drift
 * away from `docs/RELEASE.md` §3. Each assert mirrors one checklist row:
 *
 *   Dev API off · LAN binding off · backup off · cleartext off (NSC base deny + loopback-only
 *   exception) · insecure switches default-off (deny wins).
 *
 * The device-side rows of §3 (Play registration, signing) stay in the doc — they are physical
 * steps, not tree facts.
 */
class ReleaseHardeningTest {

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile
        }
        error("repo file not found from ${System.getProperty("user.dir")}: $relative")
    }

    private val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()

    @Test
    fun `backup is off (the checklist row + AGENTS rule — no backups of run data)`() {
        assertTrue("android:allowBackup must be false", manifest.contains("android:allowBackup=\"false\""))
        assertTrue(
            "the data-extraction rules must be declared (API 31+ backup path)",
            manifest.contains("android:dataExtractionRules"),
        )
    }

    @Test
    fun `cleartext is off with the NSC attached (the W6.4 and X-14 row)`() {
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
    }

    @Test
    fun `the NSC denies cleartext everywhere except the loopback hosts`() {
        val nsc = repoFile("app/src/main/res/xml/network_security_config.xml").readText()
        assertTrue("base deny", nsc.contains("<base-config cleartextTrafficPermitted=\"false\" />"))
        val loopbackBlock = nsc.substringAfter("<domain-config cleartextTrafficPermitted=\"true\">")
            .substringBefore("</domain-config>")
        for (host in listOf("127.0.0.1", "localhost", "10.0.2.2")) {
            assertTrue("loopback host $host must be allowed", loopbackBlock.contains(">$host<"))
        }
        // nothing else may appear in the allow block
        val extraDomains = Regex(">([^<]+)</domain>").findAll(loopbackBlock)
            .map { it.groupValues[1] }
            .filter { it !in setOf("127.0.0.1", "localhost", "10.0.2.2") }
            .toList()
        assertTrue("the loopback allow-list must stay loopback-only, found: $extraDomains", extraDomains.isEmpty())
    }

    @Test
    fun `the Dev API is OFF and loopback-bound by default (the first two checklist rows)`() {
        val policy = DevApiPolicy()
        assertFalse("Dev API off until the user enables it", policy.enabled)
        assertFalse("127.0.0.1 unless dev_api_remote is on", policy.allowRemote)
    }

    @Test
    fun `the insecure switches default to OFF with deny-wins (the switches row)`() {
        val state = InsecureFeatures.State()
        assertTrue("no switch ships enabled", state.enabled.isEmpty())
        assertTrue("no switch ships pre-denied either (all OFF, deny wins when set)", state.denied.isEmpty())
        assertFalse(state.relaxed)
        // the loopback switch is a KNOWN feature (MODEL_GATEWAY §4) — the audited gate of the loopback NSC allow
        assertTrue(InsecureFeatures.KNOWN.contains("ai_cleartext_loopback"))
    }
}
