# SECURITY — what is and isn't isolated (the honest model)

plan.md §22 requires this file to state **exactly** what is and isn't isolated. It is written
from the tree (paths verified 2026-11-06) and from plan §14's honest model — including the parts
that are *not* strong. Read it before trusting AutoHost with a hostile task.

**The one-sentence version: AutoHost isolates *capabilities*, not *code*. Everything a run can
reach goes through one broker path with grants, scopes, switches and audit; the code doing the
reaching is NOT sandboxed from the app — run tasks you trust.**

## 1. What IS isolated (the enforced boundaries)

### 1.1 The broker interceptor chain — the only path to a capability
Every call from every runtime (Python, flow, JS, agent) crosses one ordered chain; there is no
second action path (ADR-086/091):

```
auth(runToken) → grants → scope → insecure-feature gate → rate limit → recorder → audit → provider
```

- **Auth** (`AuthInterceptor`): the call carries the run's **token** — a bearer capability minted
  per run+attempt, bound to the grant snapshot, with an expiry; expired/revoked/malformed/
  cross-run/stale-attempt tokens are rejected from the broker's own trusted state, and revocation
  happens on cancel, terminal state and worker death (ADR-099, X-13). The token is never logged,
  persisted, exported or sent to a model (canary-tested).
- **Grants**: a call needs the capability's permission in the run's grant snapshot, which the
  user reviewed at install (and the agent's preflight re-checks at start). Dev-mode grant-all is
  an explicit opt-in and is OFF by default (X-15).
- **Scope**: package-scoped grants are enforced at call time — and since Phase 12's scope
  closure, also on **node refs** (another run's handle is `STALE_NODE`; a ref outside the act
  scope is `SCOPE_VIOLATION`), on the **spec scope flags** (`allow_coordinates`/`allow_global`
  default to refused), and on `device.app` (`list` returns only granted packages; an
  out-of-scope foreground is redacted to `{in_scope:false}`).
- **Insecure-feature gate** (ADR-059): high-risk capabilities (shell, raw_api, app_manage,
  all_files, sms, telephony, dev_api_remote, ai_cleartext_loopback) need **both** the per-task
  grant AND a global switch — off by default, deny-wins, relaxed mode never overrides a deny,
  every change audited.
- **Rate limit → recorder → audit**: every call is rate-limited, recorded for the run trace and
  written to the audit trail (sizes and hashes, not content).

### 1.2 Secrets — Keystore-wrapped, names-only in transit outside the store
- Values live in `SecretStore` (`core/data/.../secrets/`): encrypted by an AEAD cipher (Keystore
  key), addressed by `profileId` + key, **decrypted only in the main process** and handed to a
  call per declared field. Workers hold no DB handle and no Keystore keys.
- The **Redactor** holds registered values in memory only and scrubs them from logs, evidence,
  exports, audit and the SDK's `log`. Registered the moment a value is read.
- **Backups carry secret NAMES only** (ADR-082): encode refuses secret-shaped values; decode
  refuses any document carrying ciphertext-like keys — a hand-edited backup cannot smuggle
  values in; restore re-asks for the values by design.
- The SDK refuses untracked secret strings in call args, KV/state writes, checkpoints and
  results (X-12, Python half); `x-secret` fields are valid only under `profile_schema`.
- `FLAG_SECURE` on screens showing secrets; `allowBackup=false`; exports are explicit user acts.

### 1.3 The agent runtime — supervised, budgeted, one path (ADR-091)
The model is untrusted by construction: **one tool call per turn** through the SAME broker chain;
a generated tool allowlist (no shell/SMS/clipboard/app-list); argument whitelists; a policy table
(risk × sensitivity × action kind) where label hints only ever *ask*; approvals with a 60 s
timeout that **denies and stops**; TOCTOU re-validation after an approval; an observation floor
(BLOCKED-app content never reaches the model; password nodes render `{password}`); hard budgets
on turns/time/calls/tokens/actions; completion is **host-verified** — the model cannot confirm
its own success. See `docs/AGENT_RUNTIME.md` and `SECURITY`'s agent section in the user guide.

### 1.4 Exposure surfaces — small and token-guarded
- **Dev API**: OFF until enabled; token required; binds 127.0.0.1 unless the audited
  `dev_api_remote` switch is on; calls ride the same interceptor chain under a synthetic
  developer token the user scopes.
- **Webhooks** (ADR-061): localhost by default, LAN opt-in, **token mandatory in both bindings**,
  bodies capped, everything non-hook is 404/405/401.
- **RUN_TASK receiver**: the one exported trigger, guarded by a per-install token
  (`ExternalRunRequest`, constant-time checks).
- **Reverse bridge**: dials OUT to the PC; never listens; the token rides the first frame.
- **Cleartext**: denied platform-wide except the loopback hosts (`network_security_config.xml`,
  X-14); the runtime gate is the audited `ai_cleartext_loopback` switch + the URL policy.
- **FileProvider** (`file_paths.xml`): serves `files/taskdata/`, `files/` and cache `install/`
  only, `exported=false`, per-URI grants. **X-22 narrows this further** (the owner's D-23
  decision pending — today `files/` also covers `files/adb/`).
- **Import guard** (ADR-062): Chaquopy/raw-bridge routes are refused at import unless the task
  declares `android.api.direct` — which also needs the `raw_api` switch.

## 2. What is NOT isolated (the honest weaknesses)

### 2.1 The same-UID sandbox weakness — the big one
**All workers run under the app's own UID.** A script that bypasses the SDK — via Chaquopy's
`java` bridge, `ctypes`, or reading app files directly — could in principle reach app data,
including (with effort) the secrets DB files. Mitigations are *procedural and layered, not OS
isolation*: the import guard blocks the SDK routes; workers hold no live DB handle or Keystore
keys; secrets are decrypted per call in the main process; tasks install only through the
hash-verified review flow. **This is why "run tasks you trust" is the security model, not a
disclaimer.** Stronger isolation (plugin APKs with their own UID; `isolatedProcess` workers) is
future work — `isolatedProcess` feasibility with Chaquopy is unknown.

### 2.2 Label hints are not a boundary
The agent's risky-word detection (pay/send/delete/…) is best-effort, English-first, and **never
the security boundary** — scope, grants, BLOCKED apps and supervised completion are. A control
labelled "Share photo" is not checked; a mislabelled "Delete" might not ask. The user approval
flow is the compensating control.

### 2.3 The advisory challenge detector
`ChallengeDetector` classifies anti-bot/captcha walls from observable page facts. It **never
bypasses anything** (no bypass path exists in the module) but it can false-positive on ordinary
pages (the run pauses; the user resumes) and could false-negative an novel wall. Gate 10-c's
"never bypassed" holds by construction.

### 2.4 What the interceptor chain does NOT isolate
- **Reads the SDK makes of returned data** are the task's business: a capability response handed
  to the script is the script's to misuse; the chain polices the *call*, not the *use*.
- **Timing/side channels** between runs sharing the process (one broker, one registry) are not
  isolated — a run observes only its own nodes, but wall-clock effects are shared.
- **The Dev API developer token** is user-scoped but powerful by design (it is the PC pairer);
  leaking it is equivalent to running tasks by hand. Rotate it in Settings.
- **FileProvider breadth** until X-22 lands (see 1.4).

### 2.5 Known gaps being tracked
| Gap | Tracker |
|---|---|
| FileProvider covers `files/adb/` until narrowed | X-22 (owner decision D-23) |
| CPU cost of the idle a11y service unmeasured | X-20's phone half (D-25) |
| Loopback-cleartext platform compliance unverified on hardware | G13-d (D-24) |
| Same-UID isolation (this file, §2.1) | future work, plan §14.2 |

## 3. How to verify these claims
`ReleaseHardeningTest` (app) pins the manifest/NSC/policy defaults; `ScopeClosureTest` +
`AppScopeTest` pin the scope closure; `AgentSecuritySuiteTest` pins the agent canaries and
fail-closed paths; `RunTokenX13Test` pins the token invariants; `InsecureFeaturesTest` pins the
switch semantics; `ExportAndCheckpointsTest` pins the export canary; `tests/test_architecture.py`
pins the architecture rules. The suites are WRITTEN — the CI run is the verification event
(owner mode D-26; see `left.md` bucket A).

## 4. Sources
plan §14 (the honest model), §5.4 (the interceptor chain), §20 (risks), ADR-059 (switches),
ADR-061 (webhooks), ADR-062 (raw bridge), ADR-070 (browser), ADR-082 (backups), ADR-091/098/099
(agent, grants, token), X-03's coverage inventory (`docs/PROGRESS.md`), `docs/RELEASE.md` §3.
