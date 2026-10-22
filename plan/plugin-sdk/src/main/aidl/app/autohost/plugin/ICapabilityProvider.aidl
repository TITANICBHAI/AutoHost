// plugin-sdk/src/main/aidl/app/autohost/plugin/ICapabilityProvider.aidl   (Phase 11)
// External capability plugin (separate APK) exposes this via a bound service. Host discovers it with
// an intent action "app.autohost.plugin.CAPABILITY_PROVIDER" (declared in <queries>).
package app.autohost.plugin;

interface ICapabilityProvider {
    int apiLevel();                       // plugin API level, host rejects unsupported ones
    String describe();                    // JSON array of CapabilitySpec
    String availability(String envJson);  // JSON Availability for the given DeviceEnv
    String invoke(String callJson);       // {"capability","method","args","runId"} -> Envelope JSON
}
