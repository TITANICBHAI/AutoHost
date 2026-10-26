package app.autohost.capabilities.app

/**
 * P9-09: pure policy for the `device.app.manage` capability (plan §6.1 W4 — install/uninstall,
 * clear data, grant/revoke permissions; T3, DANGEROUS). Fail-closed rules the provider enforces
 * before touching the shell tier.
 */
object AppManagePolicy {

    const val CONFIRM_DETAIL = "this method is destructive: pass confirm=true"

    /** Runtime-permission names: `android.permission.X` or a custom `pkg.perm.Y` shape. */
    fun permissionVerdict(permission: String): String? = when {
        permission.isBlank() || permission.length > 220 -> "permission name has an unexpected length"
        !permission.contains('.') -> "permission must be a fully-qualified name (android.permission.X)"
        !permission.all { it.isLetterOrDigit() || it in "._" } -> "permission name contains unexpected characters"
        else -> null
    }

    fun packageVerdict(pkg: String): String? = when {
        pkg.isBlank() || pkg.length > 220 -> "package name has an unexpected length"
        !pkg.all { it.isLetterOrDigit() || it in "._-" } -> "package name contains unexpected characters"
        else -> null
    }

    /** Device-side APK path for `pm install`: shell-readable absolute path, no traversal, no spaces. */
    fun apkPathVerdict(path: String): String? = when {
        path.isBlank() || path.length > 500 -> "apk path has an unexpected length"
        !path.startsWith("/") -> "apk path must be absolute (e.g. /data/local/tmp/app.apk)"
        path.contains("..") -> "apk path must not contain '..'"
        !path.all { it.isLetterOrDigit() || it in "._/-" } -> "apk path contains unexpected characters"
        !path.lowercase().endsWith(".apk") -> "path must point to an .apk file"
        else -> null
    }

    /** `pm clear` / `pm uninstall` / install all demand the explicit confirm flag. */
    fun confirmVerdict(confirm: Boolean): String? = if (confirm) null else CONFIRM_DETAIL
}
