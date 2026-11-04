package app.autohost.runtime

import android.content.Context
import android.content.Intent
import app.autohost.broker.ICapabilityBroker
import app.autohost.runtime.api.RuntimeProvider

/**
 * P15-03: the flavor seam between the app module and the Python runtime. The `py` flavor's
 * source set (`app/src/py/`) supplies the real implementation (importing `:runtime:python`);
 * the `lite` flavor's (`app/src/lite/`) supplies the stub that contributes nothing. Main code
 * references ONLY this interface and [ActualRuntimeContributions] — the class name both flavor
 * source sets declare — so each variant compiles against exactly its own runtime set.
 */
interface AppRuntimeContributions {

    /**
     * The Python runtime for this build, or null in `lite` (a Python task then gets a typed
     * CompatReport instead of running — never a crash).
     */
    fun pythonRuntime(context: Context, brokerBinder: ICapabilityBroker): RuntimeProvider?

    /**
     * The Intent that binds the `:ui` handler worker service, or null in `lite` (no ui.handler
     * capability there; the controller reports `HANDLER_NOT_FOUND` honestly).
     */
    fun uiHandlerServiceIntent(context: Context): Intent?
}
