package app.autohost.capabilities.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class AppInfo(
    val pkg: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long?,
)

/**
 * Package queries for `device.app`. The app module injects an Android implementation; tests use fakes.
 * `QUERY_ALL_PACKAGES` (declared for the sideloaded app) makes the full list visible on API 30+.
 */
interface PackageReader {
    fun query(pkg: String): AppInfo?

    fun list(filter: String?): List<AppInfo>
}

class AndroidPackageReader(private val context: Context) : PackageReader {

    override fun query(pkg: String): AppInfo? = try {
        val packageManager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(pkg, 0)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(pkg, 0)
        }
        AppInfo(
            pkg = pkg,
            label = try {
                packageManager.getApplicationLabel(info.applicationInfo ?: return null).toString()
            } catch (e: Exception) {
                pkg
            },
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
        )
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    override fun list(filter: String?): List<AppInfo> = try {
        val packageManager = context.packageManager
        val flags = android.content.pm.PackageManager.GET_META_DATA
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(android.content.pm.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(flags)
        }
        infos.orEmpty()
            .mapNotNull { info ->
                val pkg = info.packageName ?: return@mapNotNull null
                val label = try {
                    packageManager.getApplicationLabel(info.applicationInfo ?: return@mapNotNull null).toString()
                } catch (e: Exception) {
                    pkg
                }
                AppInfo(pkg, label, info.versionName, null)
            }
            .filter { filter == null || it.pkg.contains(filter, ignoreCase = true) || it.label.contains(filter, ignoreCase = true) }
            .sortedBy { it.pkg }
    } catch (e: RuntimeException) {
        emptyList()
    }
}
