# OEM matrix — background-kill behaviour and the steps that matter

Per plan §20 ("OEM battery killers stop schedules — High likelihood"), this matrix records what
each vendor's power management does to AutoHost and the exact user steps that fix it. Rows start
as the documented mitigation set; **device-verified columns fill in from the Phase 0 S3 spike
and the soak gates** — never guessed (plan §19: every path is VERIFIED_ON devices or marked
UNVERIFIED-on-hardware).

## How to read a row

- **Kills**: what the vendor's power manager does to foreground services, exact alarms and
  accessibility services when the screen is off / the app is backgrounded.
- **Steps**: the minimum user setup for reliable unattended runs (10-minute cadence, hours).
- **Verified**: evidence pointer once the phone has run the soak (docs/GATES.md §4 / §11).

## The matrix

| Vendor / skin | Kills | Steps | Verified |
|---|---|---|---|
| Stock Android (Pixel, emulators) | Doze delays deferred alarms in deep idle; FGS survives | Battery → Unrestricted for AutoHost; keep the run notification on | PENDING-ON-HARDWARE (S3) |
| Samsung One UI | Aggressive app sleep; put unused apps to sleep; kills FGS of "sleeping" apps | Settings → Battery → Background usage limits → Never sleeping apps: add AutoHost; Battery → Unrestricted; allow autostart | PENDING-ON-HARDWARE (S3) |
| Xiaomi MIUI / HyperOS | Autostart deny by default; battery saver kills FGS; notification channel restrictions | Security app → Autostart: ON; Battery → No restrictions; Lock the app in Recents; Notification importance high | PENDING-ON-HARDWARE (S3) |
| Huawei EMUI / HarmonyOS | App launch management kills background + alarms; "close apps when locked" | Settings → Battery → App launch → AutoHost: manage manually, all three toggles ON; Battery → Unrestricted | PENDING-ON-HARDWARE (S3) |
| Oppo/Realme ColorOS | Deep sleep kills FGS; autostart gated | Battery → App battery management → Allow background; allow auto-start; lock in Recents | PENDING-ON-HARDWARE (S3) |
| OnePlus OxygenOS | Close to "optimized"; deep optimization kills alarms | Battery → Unrestricted; disable "deep optimization" for AutoHost; lock in Recents | PENDING-ON-HARDWARE (S3) |
| Vivo Funtouch/OriginOS | Background power-consuming apps whitelist | Settings → Battery → Background power consumption: allow AutoHost; Autostart ON | PENDING-ON-HARDWARE (S3) |
| Nothing/other AOSP-adjacent | Close to stock | Unrestricted battery | PENDING-ON-HARDWARE (S3) |

## The always-on recipe (vendor-independent checklist)

The Setup screen surfaces the same list (plan §4 "always-on recipe"):

1. Battery optimization **exempted** for AutoHost (`isIgnoringBatteryOptimizations` check).
2. Exact alarms **allowed** (API 31+ check; API 29-30 needs nothing).
3. The run notification channel at **high importance** (vendor "sleep" logic spares apps the
   user can see).
4. Vendor autostart/lock steps from the table above.
5. Boot self-heal ON + the one-time WRITE_SECURE_SETTINGS grant, so a vendor reboot-fest
   self-restores.
6. The 6-hour unattended soak (S3 / Gate 4) as the acceptance test — a phone that passes it at
   10-minute cadence is a phone you can trust.

## Recording evidence

When a device runs the soak: add a row to `docs/DEVICES.md` (model, build, `ro.product.cpu.abilist`,
RAM), tick the Verified cell here with the gate evidence pointer, and note anything the vendor
changed in an OS update (those regressions are real; the soak re-runs after major OS upgrades).
