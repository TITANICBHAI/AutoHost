// core/broker/src/main/aidl/app/autohost/broker/ITaskWorker.aidl
// Main -> worker process (Python runs only; flows run in the main process).
package app.autohost.broker;

import app.autohost.broker.ICapabilityBroker;

interface ITaskWorker {
    /** Start the run. runSpecJson matches schemas/broker.schema.json#/$defs/RunSpec. Returns immediately; the run executes on a worker thread. */
    void start(String runSpecJson, ICapabilityBroker broker);
    /** Cooperative controls: take effect at the next SDK call. */
    oneway void requestPause();
    oneway void requestResume();
    oneway void requestCancel();
    /** Liveness probe; worker also sends heartbeat events every <= 2 s. */
    String ping();
}
