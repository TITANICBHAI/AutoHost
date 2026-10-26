package app.autohost.fixture

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

/**
 * PermissionDialogScreen (docs/FIXTURE_APP.md): triggers a real runtime-permission request so tests can
 * exercise the popup guard against the actual system permission dialog.
 */
class PermissionDialogScreenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        Screens.bind(this, "permission")

        findViewById<Button>(R.id.fx_btn_request_permission).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        FixtureState.lastEvent = if (granted) "permission:granted" else "permission:denied"
        findViewById<TextView>(R.id.fx_permission_result).text = if (granted) "granted" else "denied"
        Screens.refresh(this)
    }

    companion object {
        const val REQUEST_CODE = 77
    }
}
