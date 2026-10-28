# Proactive Crash Review

## Scope

Review the startup and resource-loading paths for crash risks even when no
crash log is provided. Trace findings to the relevant code, state their impact,
and verify fixes where the environment allows.

## Working constraints

- Do the review directly in this session; do not launch subagents.
- Keep the review focused on the reported crash class unless the user broadens
  the scope.
- Do not claim a test or device launch succeeded unless it actually ran.

## Current findings

- `AutoHostApp.onCreate()` constructs `AppGraph`, which loads the permission
  index during startup.
- `PermissionIndex.loadFromResources()` now uses a classloader resource path
  without a leading slash and reports a missing resource explicitly.
- The other startup resource lookup, `CapabilityCatalog`, already uses a
  classloader-compatible path without a leading slash.

## Verification status

- The broker's debug and release production Kotlin compilation succeeded.
- The capability-spec resource was processed for both debug and release.
- The broker JVM tests did not run: test compilation fails because
  `BrokerPipelineTest.FakeProvider` does not implement the required
  `CapabilityProvider.providerId` property. The resource-loading test has
  therefore not yet confirmed the fix.
- No APK launch on a physical device has been verified.

## Next steps when work resumes

1. Add the required `providerId` to the broker test fake.
2. Run `:core:broker:test`, then the full `bash scripts/test-jvm.sh` check.
3. Build the debug APK and verify first launch on a target device.

This document records the current scope and status. It does not authorize
additional code changes by itself.
