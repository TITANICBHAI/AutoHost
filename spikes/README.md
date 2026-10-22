# Phase 0 spike workspace

This directory is reserved for throwaway feasibility experiments. It is not a
production Android module and must be removed before Phase 1.

## Planned areas

- `s1-python/` — Chaquopy, worker-process, AIDL, writable-import, and ABI checks.
- `s2-accessibility/` — accessibility reads/actions, screenshot consent, and app-launch checks.
- `s3-background/` — alarm, foreground-service, keep-awake, and recovery checks.
- `s4-shell/` — ADB transport, shell commands, secure-settings, and UiAutomation checks.
- `s5-ime/` — text commit, clipboard, and IME-selection checks.

Create an area's files only when its prerequisites and authorization are
available. Keep measurements and verdicts in `plan/docs/SPIKES.md`; record
device/API details and unverified paths in `plan/docs/DEVICES.md`.

Do not put production code, credentials, personal screenshots, or copied
AGPL/GPL code here. The clean-room and attribution requirements in
`plan/docs/REFERENCES.md` apply to every spike.
