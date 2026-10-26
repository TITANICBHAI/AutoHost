// core/broker/src/main/aidl/app/autohost/broker/ICapabilityBroker.aidl
// Worker -> main process. All payloads are UTF-8 JSON strings (see schemas/broker.schema.json).
package app.autohost.broker;

interface ICapabilityBroker {
    /**
     * Blocking capability call. Returns an Envelope JSON string:
     *   {"ok":true,"result":...,"meta":{...}}  or  {"ok":false,"error":{"code":...,"message":...}}
     * Payloads larger than ~100 KB (screenshots, big dumps) are written under the run's evidence dir
     * and returned as {"path": "..."} — never sent through Binder (1 MB transaction limit).
     * The broker pipeline (interceptors) runs: auth -> grants -> scope -> insecure-feature gate
     * -> rate limit -> recorder -> audit -> provider.
     */
    String call(String runToken, String capability, String method, String argsJson);

    /** Fire-and-forget event: log, progress, stage_begin/end, evidence, result, next_run, heartbeat, incident. */
    oneway void emit(String runToken, String eventJson);

    /** Granted capabilities + live availability + active provider chain, for SDK introspection (autohost.capabilities()). */
    String describe(String runToken);
}
