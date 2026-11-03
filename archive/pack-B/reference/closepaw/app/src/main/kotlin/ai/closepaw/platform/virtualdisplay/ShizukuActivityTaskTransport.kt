package ai.closepaw.platform.virtualdisplay

import android.util.Log
import java.lang.reflect.InvocationTargetException

/** Transport layer for display-scoped task cleanup through IActivityTaskManager. */
internal class ShizukuActivityTaskTransport(
        private val activityTaskManagerProxy: () -> Any
) {
        constructor(proxyProvider: ShizukuServiceProxyProvider) : this(
                { proxyProvider.activityTaskManagerProxy() }
        )

        companion object {
                private const val TAG = "ShizukuActivityTask"
        }

        fun removeRootTasksOnDisplay(displayId: Int): Int {
                if (displayId < 0) {
                        Log.w(
                                TAG,
                                "VD task cleanup before release: displayId=$displayId removed=-1 err=invalid-display"
                        )
                        return -1
                }

                return try {
                        val proxy = activityTaskManagerProxy()
                        val rootTaskInfos = getAllRootTaskInfosOnDisplay(proxy, displayId)
                        var removed = 0

                        rootTaskInfos.forEach { info ->
                                val taskId = rootTaskId(info)
                                if (removeTask(proxy, taskId)) removed++
                        }

                        Log.i(
                                TAG,
                                "VD task cleanup before release: displayId=$displayId removed=$removed ok"
                        )
                        removed
                } catch (e: Exception) {
                        Log.w(
                                TAG,
                                "VD task cleanup before release: displayId=$displayId removed=-1 err=${e.summary()}",
                                e
                        )
                        -1
                }
        }

        private fun getAllRootTaskInfosOnDisplay(proxy: Any, displayId: Int): List<Any> {
                val method =
                        proxy.javaClass.getMethod(
                                "getAllRootTaskInfosOnDisplay",
                                Int::class.javaPrimitiveType
                        )
                val result = method.invoke(proxy, displayId) ?: return emptyList()
                return (result as? List<*>)
                        ?.filterNotNull()
                        ?: throw IllegalStateException(
                                "getAllRootTaskInfosOnDisplay returned ${result.javaClass.name}"
                        )
        }

        private fun removeTask(proxy: Any, taskId: Int): Boolean {
                val method =
                        proxy.javaClass.getMethod(
                                "removeTask",
                                Int::class.javaPrimitiveType
                        )
                return method.invoke(proxy, taskId) as? Boolean ?: false
        }

        private fun rootTaskId(rootTaskInfo: Any): Int {
                return rootTaskInfo.javaClass.getField("taskId").getInt(rootTaskInfo)
        }

        private fun Throwable.summary(): String {
                val cause = if (this is InvocationTargetException) targetException else this
                val name = cause::class.java.simpleName
                val message = cause.message
                return if (message.isNullOrBlank()) name else "$name: $message"
        }
}
