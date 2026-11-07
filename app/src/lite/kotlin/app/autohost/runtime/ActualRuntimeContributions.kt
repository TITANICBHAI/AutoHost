package app.autohost.runtime

import android.content.Context
import android.content.Intent
import app.autohost.broker.ICapabilityBroker
import app.autohost.runtime.api.RuntimeProvider

/**
 * P15-03: the `lite` flavor — `:runtime:python` and the Chaquopy plugin are absent from the
 * graph. Python tasks get a typed CompatReport instead of running (G15-b); ui.handler reports
 * `HANDLER_NOT_FOUND`. Flows, JS and agent runtimes are unaffected.
 */
class ActualRuntimeContributions : AppRuntimeContributions {

    override fun pythonRuntime(context: Context, brokerBinder: ICapabilityBroker): RuntimeProvider? = null

    override fun uiHandlerServiceIntent(context: Context): Intent? = null
}
