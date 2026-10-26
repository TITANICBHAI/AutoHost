package app.autohost.capabilities.projection

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Asks the user for screen capture consent (MediaProjection) and hands the result to the service via
 * [ProjectionHolder]. The AutoAcceptGate may have already dismissed the dialog for host-initiated flows
 * (plan §5.7); the activity completes either way — a denial is an honest, typed failure downstream.
 */
class ProjectionConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ProjectionHolder.active()) {
            finish() // already granted; nothing to ask
            return
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(ProjectionHolder.consentIntent(this), REQUEST_CODE)
        } catch (e: RuntimeException) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            ProjectionHolder.storePending(resultCode, data)
            ProjectionService.start(this)
        }
        finish()
    }

    companion object {
        const val REQUEST_CODE = 4101
    }
}
