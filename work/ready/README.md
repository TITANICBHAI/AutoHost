# AutoHost

An Android app that runs small automations on your phone — Python scripts, recorded flows, JavaScript — through a
permission broker. Every automation shows what it wants to do before it can run, can be stopped from the notification,
and keeps a log. *A new automation costs a script, not a new app.*

**Status: alpha, not yet verified on a real phone.** The engine, the Python SDK and a fake-device simulator are tested;
the Android app builds in CI but has not been run on hardware yet (see `STATUS.md`). Expect rough edges.

## Install (sideload)
1. Download the newest APK from the repository's *Actions → Android debug APK* artifact (or a *Release* once there is one).
2. Open it on the phone and allow installing from that source.
3. To control other apps AutoHost needs its **Accessibility** service. On Android 13+ the switch can be greyed out for
   apps that did not come from a store: open *Settings → Apps → AutoHost → ⋮ → Allow restricted settings*, then enable it.

## What it can and cannot do
Can: read the screen and tap in apps you choose, open apps, call websites you list, post notifications, run on schedules.
Cannot, by design: solve CAPTCHAs, hide that it is an automation, harvest credentials. Some things Android itself blocks
(background limits on some phone brands, restricted settings, Advanced Protection mode).

## For contributors and coding agents
Read `AGENTS.md`, then `STATUS.md`. The plan for getting to a first usable release is in `work/plan/`.
Licence: Apache-2.0. Third-party notices: `THIRD_PARTY_NOTICES.md`. Security notes: `SECURITY.md`.
User guide: `docs/USER_GUIDE.md`. Battery-killer steps per phone brand: `docs/OEM_MATRIX.md`.
