package app.autohost.core.engine.tasks

import app.autohost.core.common.AhJson
import java.io.File
import java.io.InputStream

/**
 * P2-04 (engine half): the single import pipeline every source goes through (plan §8.3).
 *
 * Sources — file picker / Open-with, share sheet, HTTPS URL, dev folder, samples, New-from-template — only
 * differ in how bytes arrive ([Source]); everything after is identical: hash → stage → validate → scan →
 * compat → report. Install only happens on explicit approval (Permission Review for new bundles, one-tap diff
 * approval in dev mode). A failed review never leaves a partially installed task behind.
 *
 * The UI half (picker activity, share targets, URL fetch) lives in `:app` (`importer/`).
 */
class ImportPipeline(
    private val extractor: BundleExtractor,
    private val scanner: ImportScanner = ImportScanner { null },
    private val compat: CompatChecker? = null,
    /**
     * P8-12: the raw-bridge import guard (on by default, plan §18 P8 "android.api.direct (import
     * guard on/off)"). ON: a python bundle whose scan finds raw-bridge usage must declare
     * `android.api.direct` in needs.capabilities or the import is refused with RAW_BRIDGE_UNDECLARED.
     * OFF: the owner's explicit choice (Settings → Developer & security; every disable is audited).
     */
    private val rawBridgeGuard: Boolean = true,
    /**
     * P11-04 (ADR-080): the installed pack ids, when the caller knows them (the app wires
     * PackController::installedIds). Missing `uses_packs` become non-blocking warning rows; when
     * the seam is absent (PC-side reviews) no pack rows are produced and run-start resolution
     * stays the honest gate.
     */
    private val installedPackIds: (() -> Set<String>)? = null,
) {

    /** Where the bytes came from — recorded in the report for provenance. */
    sealed interface Source {
        val label: String

        data class Bytes(override val label: String, val open: () -> InputStream) : Source
        data class DevFolder(val dir: File) : Source {
            override val label: String get() = "dev-folder:${dir.name}"
        }

        data class Sample(val dir: File) : Source {
            override val label: String get() = "sample:${dir.name}"
        }
    }

    /** Outcome of a review: staged bundle + full report. `staged == null` means the bundle was rejected. */
    data class Review(val staged: BundleExtractor.Staged?, val report: ImportReport) {
        val canInstall: Boolean get() = staged != null && !report.blocking
    }

    /**
     * Validate a bundle end-to-end without installing. The caller decides whether to install
     * ([BundleExtractor.install]) or discard (clean the staged dir) after Permission Review.
     */
    fun review(source: Source): Review {
        val (staged, base) = when (source) {
            is Source.Bytes -> stageFromStream(source)
            is Source.DevFolder -> stageFromDir(source.dir, source.label)
            is Source.Sample -> stageFromDir(source.dir, source.label)
        }
        if (staged == null) return Review(null, base)

        val manifest = try {
            AhJson.json.decodeFromString(app.autohost.core.model.TaskManifest.serializer(), staged.manifest)
        } catch (e: Exception) {
            return Review(clean(staged), base.copy(errors = base.errors + ImportReport.Issue("", "DESERIALIZE", "manifest unreadable")))
        }

        // P2-13: python bundles get the import scan; other runtimes pass through.
        val scanSummary = if (manifest.runtime.type == "python") {
            when (source) {
                is Source.DevFolder -> scanner.scan(source.dir)?.toSummary()
                is Source.Sample -> scanner.scan(source.dir)?.toSummary()
                is Source.Bytes -> null // zipped streams: the staged dir is transient; scan runs post-install
            }
        } else null

        // P2-12: device compatibility rows for required/optional capabilities and apps.
        val compatRows = compat?.check(manifest, manifest.configSchema)

        // P8-12: the raw-bridge import guard — usage must be declared as a capability need.
        val rawGuardIssues = if (rawBridgeGuard && scanSummary != null && scanSummary.rawBridge.isNotEmpty()) {
            val declared = manifest.needs.capabilities.any { it.id == "android.api.direct" }
            if (declared) {
                emptyList()
            } else {
                listOf(
                    ImportReport.Issue(
                        scanSummary.rawBridge.first(),
                        "RAW_BRIDGE_UNDECLARED",
                        "the script uses the raw Android bridge (autohost.android / Chaquopy) without declaring " +
                            "android.api.direct in needs.capabilities — it would be refused at run time anyway " +
                            "(grant + raw_api switch)",
                    ),
                )
            }
        } else emptyList()

        val report = base.copy(scan = scanSummary, compat = compatRows.orEmpty(), errors = base.errors + rawGuardIssues)
        return Review(staged, report.copy(warnings = report.warnings + packWarnings(manifest)))
    }

    /** P11-04 (ADR-080): non-blocking warnings for `uses_packs` the device cannot resolve yet. */
    private fun packWarnings(manifest: app.autohost.core.model.TaskManifest): List<ImportReport.Issue> {
        if (manifest.usesPacks.isEmpty()) return emptyList()
        val known = installedPackIds?.invoke() ?: return emptyList()
        return manifest.usesPacks.filter { it !in known }.map { id ->
            ImportReport.Issue("uses_packs/$id", "PACK_MISSING", "app pack '$id' is not installed — the run will start without it (install the .aapack to fix)")
        }
    }

    private fun clean(staged: BundleExtractor.Staged): BundleExtractor.Staged? {
        if (staged.dir.path.contains("import-")) staged.dir.deleteRecursively()
        return null
    }

    private fun stageFromStream(source: Source.Bytes): Pair<BundleExtractor.Staged?, ImportReport> = try {
        val staged = extractor.stage(source.open(), source.label)
        val manifest = AhJson.json.decodeFromString(app.autohost.core.model.TaskManifest.serializer(), staged.manifest)
        staged to ImportReport(
            source = source.label,
            bundleName = source.label.substringAfterLast('/'),
            sha256 = staged.sha256,
            taskId = staged.taskId,
            taskName = manifest.name,
            version = staged.version,
            manifestSummary = manifestSummary(manifest),
        )
    } catch (e: BundleExtractor.BundleException) {
        null to failure(source.label, source.label.substringAfterLast('/'), e.rule, e.message ?: "bundle rejected")
    } catch (e: Exception) {
        null to failure(source.label, source.label.substringAfterLast('/'), "IO", e.message ?: "unreadable bundle")
    }

    private fun stageFromDir(dir: File, label: String): Pair<BundleExtractor.Staged?, ImportReport> {
        val manifestFile = File(dir, BundleExtractor.MANIFEST_ENTRY)
        if (!manifestFile.isFile) {
            return null to failure(dir.canonicalPath, dir.name, "NO_MANIFEST", "task folder has no task.json")
        }
        val manifest = try {
            AhJson.json.decodeFromString(app.autohost.core.model.TaskManifest.serializer(), manifestFile.readText())
        } catch (e: Exception) {
            return null to failure(dir.canonicalPath, dir.name, "MANIFEST_INVALID", "task.json could not be parsed: ${e.message}")
        }
        val validation = ManifestValidator.validateJson(manifestFile.readText())
        if (!validation.second.ok) {
            val issue = validation.second.errors.first()
            return null to failure(
                dir.canonicalPath, dir.name, issue.rule,
                validation.second.errors.joinToString("; ") { it.describe() },
            )
        }
        val sha = dirHash(dir)
        val staged = BundleExtractor.Staged(dir, sha, manifestFile.readText(), manifest.id, manifest.version, manifest.runtime.entry)
        return staged to ImportReport(
            source = label,
            bundleName = dir.name,
            sha256 = sha,
            taskId = manifest.id,
            taskName = manifest.name,
            version = manifest.version,
            manifestSummary = manifestSummary(manifest),
        )
    }

    private fun failure(source: String, bundleName: String, rule: String, message: String) =
        ImportReport(source = source, bundleName = bundleName, sha256 = "", errors = listOf(ImportReport.Issue("", rule, message)))

    private fun manifestSummary(manifest: app.autohost.core.model.TaskManifest): String =
        "${manifest.runtime.type} · ${manifest.needs.capabilities.size} capabilities · ${manifest.needs.apps.size} apps"

    companion object {
        /**
         * Directory hash for dev-folder "bundles" (no zip): SHA-256 over the manifest bytes plus every file's
         * relative path and size, sorted — stable while contents are unchanged.
         */
        fun dirHash(dir: File): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val manifest = File(dir, BundleExtractor.MANIFEST_ENTRY)
            digest.update(manifest.readBytes())
            val files = dir.walkTopDown().filter { it.isFile && it != manifest }.map { it.relativeTo(dir).path to it.length() }.toList().sortedBy { it.first }
            for ((path, size) in files) {
                digest.update(path.encodeToByteArray())
                digest.update(":".encodeToByteArray())
                digest.update(size.toString().encodeToByteArray())
                digest.update("\n".encodeToByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
