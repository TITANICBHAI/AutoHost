// core/broker/src/main/aidl/app/autohost/broker/IUiHandlerWorker.aidl
// Main -> worker process (:ui, P7-03). Task-UI callbacks run here so they never block long runs.
package app.autohost.broker;

import app.autohost.broker.ICapabilityBroker;

interface IUiHandlerWorker {
    /**
     * Run one @ui.handler call to completion. handlerSpecJson mirrors
     * autohost._runtime.run_ui_handler's HandlerSpec; the returned JSON string has the run outcome
     * shape ({"status": ..., "result"/"reason"/"message"}). Blocking, capped at 30 s by both sides.
     */
    String callHandler(String handlerSpecJson, ICapabilityBroker broker);
    /** Liveness probe. */
    String ping();
}
