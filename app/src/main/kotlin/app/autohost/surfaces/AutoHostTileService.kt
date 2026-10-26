package app.autohost.surfaces

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.autohost.AutoHostApp
import app.autohost.MainActivity
import app.autohost.R
import app.autohost.core.common.AhLog
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch

/**
 * P7-07: the generic Quick Settings tiles (plan §10.3 — "AutoHost 1–5, bindable to any task/
 * profile"). One concrete service per slot ([Tile1Service]..[Tile5Service], one manifest entry
 * each, since a TileService component is exactly one tile); all behaviour lives here:
 *
 * - bound → clicking starts that task (optionally profile/stage) with the `surface` trigger;
 * - unbound → clicking opens the Surfaces screen where the binding is made;
 * - while listening, the tile's subtitle names the bound task (and "running" while it runs).
 *
 * FGS note: a tile click is a user interaction, so starting the run foreground service from
 * `onClick` is allowed on API 31+; if an OEM refuses anyway the run still starts and only the
 * notification is missing (RunForegroundService.start fails honest, logs).
 */
abstract class AutoHostTileService : TileService() {

    /** 1-based slot this concrete service represents. */
    protected abstract val slot: Int

    private val graph by lazy { (application as AutoHostApp).graph }
    private val prefs by lazy { graph.surfacesPrefs }

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val binding = prefs.tileBinding(slot)
        if (binding == null) {
            openSurfaces()
            return
        }
        val pending = goAsync()
        graph.appScope.launch {
            try {
                graph.surfaceRuns.start(
                    taskId = binding.taskId,
                    profileId = binding.profileId,
                    stage = binding.stage,
                    source = "tile$slot",
                )
            } finally {
                pending.finish()
            }
        }
        refreshTile()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        // The user took the tile out of the panel; the binding itself stays (re-adding restores it).
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val binding = prefs.tileBinding(slot)
        if (binding == null) {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = getString(R.string.tile_unbound_subtitle)
        } else {
            val taskName = runCatching {
                kotlinx.coroutines.runBlocking { graph.data.tasks.byId(binding.taskId)?.name }
            }.getOrNull()
            val running = graph.runStore.runs.value.any { it.active && it.taskId == binding.taskId }
            tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = when {
                running -> getString(R.string.tile_running_subtitle)
                !taskName.isNullOrEmpty() -> taskName
                else -> binding.taskId
            }
        }
        tile.updateTile()
    }

    /** Android 10+ blocks background activity starts — but TileService may launch via its own window token. */
    private fun openSurfaces() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_ROUTE, "surfaces")
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = android.app.PendingIntent.getActivity(
                    this,
                    slot,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: RuntimeException) {
            AhLog.w("tile", "could not open surfaces from tile", buildJsonObject {
                put("slot", slot)
            })
        }
    }
}

class Tile1Service : AutoHostTileService() { override val slot = 1 }
class Tile2Service : AutoHostTileService() { override val slot = 2 }
class Tile3Service : AutoHostTileService() { override val slot = 3 }
class Tile4Service : AutoHostTileService() { override val slot = 4 }
class Tile5Service : AutoHostTileService() { override val slot = 5 }
