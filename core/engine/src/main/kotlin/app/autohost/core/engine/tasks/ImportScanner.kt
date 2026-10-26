package app.autohost.core.engine.tasks

import java.io.File

/**
 * P2-13: static import scan seam (plan §15). The real scanner runs Python's `ast` module inside the Python
 * runtime (`:runtime:python` → `ImportScannerPy`, script `sdk-python/autohost/_scan.py`); the JVM side only
 * classifies and renders the result so it stays testable without a worker.
 *
 * Buckets per plan §15: stdlib / bundled / vendored / unavailable / known desktop-only, with suggestions.
 */
fun interface ImportScanner {
    /** Scan a bundle directory (the manifest's siblings). Null = no python files or scanner unavailable. */
    fun scan(bundleDir: File): ScanReport?
}

data class ScanReport(
    val files: Int = 0,
    val stdlib: Int = 0,
    val bundled: Int = 0,
    val vendored: Int = 0,
    val unavailable: List<String> = emptyList(),
    val desktopOnly: List<Hit> = emptyList(),
    /** P8-12: files using the raw Android bridge (Chaquopy passthroughs / autohost.android calls). */
    val rawBridge: List<String> = emptyList(),
) {
    data class Hit(val module: String, val file: String, val suggestion: String)

    /** Modules imported but neither stdlib nor declared in the manifest's python_deps. */
    val hasProblems: Boolean get() = unavailable.isNotEmpty() || desktopOnly.isNotEmpty()

    fun toSummary(): ImportReport.ScanSummary = ImportReport.ScanSummary(
        files = files,
        stdlib = stdlib,
        bundled = bundled,
        vendored = vendored,
        unavailable = unavailable,
        desktopOnly = desktopOnly.map { ImportReport.DesktopHit(it.module, it.file, it.suggestion) },
        rawBridge = rawBridge,
    )
}

/**
 * Pure classifier shared by the Kotlin side: given the raw module names found by the AST scan and the
 * manifest's declared deps, produce the report buckets. The desktop-only list is from plan §15.
 */
object ImportClassifier {

    /** Known desktop-only / PC-only modules that will never work inside a task on the phone. */
    val DESKTOP_ONLY: Map<String, String> = mapOf(
        "playwright" to "use the browser capability (device.browser) once shipped",
        "patchright" to "use the browser capability (device.browser) once shipped",
        "selenium" to "use the browser capability (device.browser) once shipped",
        "pyautogui" to "use device.ui for screen control",
        "pynput" to "keyboard/mouse control is not available on device; use device.ui",
        "tkinter" to "no desktop GUI on device; use ui.* prompts or a task UI",
        "PyQt5" to "no desktop GUI on device; use ui.* prompts or a task UI",
        "PyQt6" to "no desktop GUI on device; use ui.* prompts or a task UI",
        "PySide2" to "no desktop GUI on device; use ui.* prompts or a task UI",
        "PySide6" to "no desktop GUI on device; use ui.* prompts or a task UI",
        "win32api" to "Windows-only; not available on device",
        "win32com" to "Windows-only; not available on device",
        "winreg" to "Windows-only; not available on device",
        "ctypes" to "bypasses the broker; declare needs.capabilities instead (android.api.direct requires a grant)",
        "adb_shell" to "talk to the device through the host broker, not from inside it",
    )

    private val SUGGESTION_FALLBACK = "not bundled with the host; add it to python_deps.vendored or rewrite against the SDK"

    fun classify(
        topLevelModules: Set<String>,
        bundled: Set<String>,
        vendored: Set<String>,
        files: Map<String, List<String>>, // file → top-level module names imported there
    ): ScanReport {
        var stdlibCount = 0
        var bundledCount = 0
        var vendoredCount = 0
        val unavailable = sortedSetOf<String>()
        val desktop = sortedMapOf<String, ScanReport.Hit>()
        for ((file, modules) in files) {
            for (module in modules) {
                when {
                    // desktop-only / bypass routes win over everything else (plan §15 import guard)
                    module in DESKTOP_ONLY -> desktop.putIfAbsent(
                        module,
                        ScanReport.Hit(module, file, DESKTOP_ONLY[module] ?: SUGGESTION_FALLBACK),
                    )
                    module in STDLIB_TOP_LEVEL -> stdlibCount += 1
                    module in bundled || bundled.any { module == it || module.startsWith("$it.") } -> bundledCount += 1
                    module in vendored || vendored.any { module == it || module.startsWith("$it.") } -> vendoredCount += 1
                    else -> unavailable.add(module)
                }
            }
        }
        // Modules counted as bundled/vendored are not "unavailable" even if also undeclared elsewhere.
        unavailable.removeAll(bundled)
        unavailable.removeAll(vendored)
        DESKTOP_ONLY.keys.forEach { unavailable.remove(it) }
        return ScanReport(
            files = files.size,
            stdlib = stdlibCount,
            bundled = bundledCount,
            vendored = vendoredCount,
            unavailable = unavailable.toList(),
            desktopOnly = desktop.values.toList(),
        )
    }

    /** stdlib top-level module names (Python 3.13); trimmed to what tasks realistically import. */
    val STDLIB_TOP_LEVEL: Set<String> = setOf(
        "abc", "argparse", "array", "ast", "asyncio", "base64", "binascii", "bisect", "calendar", "cmath",
        "collections", "concurrent", "contextlib", "contextvars", "copy", "copyreg", "csv",
        "dataclasses", "datetime", "decimal", "difflib", "enum", "errno", "fnmatch", "fractions", "functools",
        "gc", "getopt", "getpass", "glob", "graphlib", "gzip", "hashlib", "heapq", "hmac", "html", "http",
        "imaplib", "importlib", "inspect", "io", "ipaddress", "itertools", "json", "keyword", "linecache",
        "locale", "logging", "math", "mimetypes", "mmap", "multiprocessing", "numbers", "operator", "os",
        "pathlib", "pdb", "pickle", "platform", "plistlib", "pprint", "queue", "quopri", "random", "re",
        "reprlib", "secrets", "select", "shelve", "shlex", "shutil", "signal", "site", "socket", "sqlite3",
        "ssl", "stat", "statistics", "string", "stringprep", "struct", "subprocess", "sys", "sysconfig",
        "tarfile", "tempfile", "textwrap", "threading", "time", "timeit", "traceback",
        "tracemalloc", "types", "typing", "unicodedata", "unittest", "urllib", "uuid", "warnings", "weakref",
        "webbrowser", "wsgiref", "xml", "xmlrpc", "zipfile", "zipapp", "zlib", "zoneinfo", "__future__",
    )
}
