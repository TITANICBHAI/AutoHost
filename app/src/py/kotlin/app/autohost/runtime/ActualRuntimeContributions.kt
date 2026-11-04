package app.autohost.runtime

import android.content.Context
import android.content.Intent
import app.autohost.broker.ICapabilityBroker
import app.autohost.runtime.api.RuntimeProvider
import app.autohost.runtime.python.PythonRuntimeProvider
import app.autohost.runtime.python.worker.UiHandlerWorkerService

/** P15-03: the `py` flavor — the full Python runtime (Chaquopy) is on the classpath. */
class ActualRuntimeContributions : AppRuntimeContributions {

    override fun pythonRuntime(context: Context, brokerBinder: ICapabilityBroker): RuntimeProvider =
        PythonRuntimeProvider(context, brokerBinder)

    override fun uiHandlerServiceIntent(context: Context): Intent =
        Intent(context, UiHandlerWorkerService::class.java)
}
